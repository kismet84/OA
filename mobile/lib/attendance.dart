import 'package:flutter/material.dart';

import 'theme.dart';

import 'api.dart';
import 'ui.dart';
import 'requests.dart';
import 'validation.dart';

const attendanceLabels = {
  'NORMAL': '正常',
  'LATE': '迟到',
  'EARLY': '早退',
  'LATE_EARLY': '迟到及早退',
  'MISSING': '缺卡',
  'LEAVE': '请假',
  'FIELD': '外勤',
  'REST': '休息日',
  'EXCLUDED': '未纳入统计',
  'FUTURE': '未来日期',
  'IN_PROGRESS': OaTokens.statusInProgressLabel,
  'RECORDED': '无打卡记录（规则关闭）',
  'PUNCHED': '已打卡（规则关闭）',
};

class AttendancePage extends StatefulWidget {
  final Api api;
  final Json user;
  final int? memberId;
  const AttendancePage({
    super.key,
    required this.api,
    required this.user,
    this.memberId,
  });
  @override
  State<AttendancePage> createState() => _AttendanceState();
}

class _AttendanceState extends State<AttendancePage>
    with Guard<AttendancePage> {
  late String month =
      '${shanghaiNow().year}-${shanghaiNow().month.toString().padLeft(2, '0')}';
  List<Json> summaries = [], days = [];
  int? selected;
  final monthInput = TextEditingController();
  @override
  void initState() {
    super.initState();
    selected = widget.memberId ?? widget.user['id'];
    monthInput.text = month;
    load();
  }

  @override
  void dispose() {
    monthInput.dispose();
    super.dispose();
  }

  Future<void> load() => run(() async {
    if (!RegExp(r'^\d{4}-\d{2}$').hasMatch(monthInput.text)) {
      throw ApiError('月份请使用 YYYY-MM');
    }
    month = monthInput.text;
    summaries = records(
      await widget.api.call('/attendance/summary?month=$month'),
    );
    final parts = month.split('-').map(int.parse).toList();
    final end = DateTime(parts[0], parts[1] + 1, 0).day;
    days = records(
      await widget.api.call(
        '/attendance/daily?userId=$selected&from=$month-01&to=$month-$end',
      ),
    );
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('我的本月考勤')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space20),
      children: [
        feedback,
        TextField(
          controller: monthInput,
          decoration: const InputDecoration(labelText: '考勤月份 YYYY-MM'),
        ),
        OutlinedButton(
          onPressed: busy ? null : load,
          child: const Text('查询考勤'),
        ),
        if (summaries.length > 1)
          DropdownButton<int>(
            value: selected,
            items: [
              for (final s in summaries)
                DropdownMenuItem(
                  value: s['user_id'] as int,
                  child: Text('${s['user_name']}'),
                ),
            ],
            onChanged: busy
                ? null
                : (id) {
                    selected = id;
                    load();
                  },
          ),
        for (final s in summaries.where((s) => s['user_id'] == selected))
          Text(
            '应出勤 ${s['expectedDays']} · 正常 ${s['normalDays']} · 迟到 ${s['lateCount']} · 早退 ${s['earlyCount']} · 缺卡 ${s['missingCount']} · 请假 ${s['leaveDays']} · 外勤 ${s['fieldDays']}',
          ),
        const Text('北京时间；规则生效前和入职前不判缺卡，当天未下班显示进行中。'),
        for (final d in days)
          ListTile(
            title: Text(
              '${d['day']} · ${attendanceLabels[d['status']] ?? d['status']}',
              style: TextStyle(
                color:
                    [
                      'LATE',
                      'EARLY',
                      'LATE_EARLY',
                      'MISSING',
                    ].contains(d['status'])
                    ? Theme.of(context).colorScheme.error
                    : null,
              ),
            ),
            subtitle: Text(
              '首卡 ${timeOf(d['firstPunch'])}\n末卡 ${timeOf(d['lastPunch'])}\n迟到 ${d['lateMinutes']} 分 · 早退 ${d['earlyMinutes']} 分 ${d['missingPunch']}\n关联单号 ${d['requestIds']}',
            ),
            trailing:
                selected == widget.user['id'] &&
                    [
                      'LATE',
                      'EARLY',
                      'LATE_EARLY',
                      'MISSING',
                    ].contains(d['status'])
                ? TextButton(
                    onPressed: () => go(
                      context,
                      RequestFormPage(
                        api: widget.api,
                        initialKind: 'CORRECTION',
                        initialDraft: {
                          'kind': 'CORRECTION',
                          'correctionAt':
                              '${d['day']}T${d['missingPunch'] == '上班卡' ? '09:00' : '18:00'}',
                          'punchKind': d['field'] == true ? 'FIELD' : 'NORMAL',
                        },
                      ),
                    ),
                    child: const Text('发起补卡'),
                  )
                : null,
          ),
      ],
    ),
  );
}
