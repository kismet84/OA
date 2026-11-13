import 'package:flutter/material.dart';

import 'api.dart';
import 'attendance.dart';
import 'ui.dart';

const workbenchKinds = {
  'LEAVE': '请假',
  'TRAVEL': '出差',
  'OUTING': '外出',
  'OVERTIME': '加班',
  'CORRECTION': '补卡',
  'EXPENSE': '报销',
};

class WorkbenchPage extends StatefulWidget {
  final Api api;
  const WorkbenchPage({super.key, required this.api});
  @override
  State<WorkbenchPage> createState() => _WorkbenchState();
}

class _WorkbenchState extends State<WorkbenchPage> with Guard<WorkbenchPage> {
  Json? data;
  final from = TextEditingController(), to = TextEditingController();
  String scope = 'self', department = '';
  List<Json> members = [];
  int memberOffset = 0;
  Future<void> loadMembers() => run(() async {
    final rows = records(
      await widget.api.call(
        '/workbench/members?${Uri(queryParameters: {'offset': '$memberOffset', if (department.isNotEmpty) 'departmentId': department}).query}',
      ),
    );
    if (mounted) setState(() => members = rows);
  });
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    from.dispose();
    to.dispose();
    super.dispose();
  }

  Future<void> load() => run(() async {
    final q = Uri(
      queryParameters: {
        'scope': scope,
        if (from.text.isNotEmpty) 'from': from.text,
        if (to.text.isNotEmpty) 'to': to.text,
        if (department.isNotEmpty) 'departmentId': department,
      },
    ).query;
    final result = await widget.api.call('/workbench/summary?$q') as Json;
    if (mounted) {
      setState(() {
        data = result;
        members = [];
        memberOffset = 0;
        from.text = result['from'];
        to.text = result['to'];
      });
    }
  });
  @override
  Widget build(BuildContext context) {
    final d = data, a = d?['attendance'], p = d?['approvals'];
    return Scaffold(
      appBar: AppBar(title: const Text('考勤与审批汇总')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('北京时间 · 默认当前自然月 · 含首尾最多366天'),
          TextField(
            controller: from,
            decoration: const InputDecoration(
              labelText: '汇总开始日期',
              hintText: 'YYYY-MM-DD',
            ),
          ),
          TextField(
            controller: to,
            decoration: const InputDecoration(
              labelText: '汇总结束日期',
              hintText: 'YYYY-MM-DD',
            ),
          ),
          if (d?['canViewTeam'] == true)
            DropdownButtonFormField<String>(
              initialValue: scope,
              decoration: const InputDecoration(labelText: '统计范围'),
              items: [
                const DropdownMenuItem(value: 'self', child: Text('本人')),
                DropdownMenuItem(
                  value: 'team',
                  child: Text(d?['canFilterDepartment'] == true ? '全公司' : '团队'),
                ),
              ],
              onChanged: busy
                  ? null
                  : (v) => setState(() {
                      scope = v!;
                      department = '';
                    }),
            ),
          if (d?['canFilterDepartment'] == true && scope == 'team')
            DropdownButtonFormField<String>(
              key: ValueKey(scope),
              initialValue: department,
              decoration: const InputDecoration(labelText: '统计部门（含下级）'),
              items: [
                const DropdownMenuItem(value: '', child: Text('全公司')),
                for (final x in d!['departments'])
                  DropdownMenuItem(value: '${x['id']}', child: Text(x['name'])),
              ],
              onChanged: busy ? null : (v) => setState(() => department = v!),
            ),
          FilledButton(
            onPressed: busy ? null : load,
            child: const Text('查询汇总'),
          ),
          feedback,
          if (d != null) ...[
            Text('${d['from']} 至 ${d['to']} · 在职人数 ${d['people']}'),
            section('考勤异常'),
            Text(
              '迟到 ${a['anomalies']?['lateCount'] ?? 0} · 早退 ${a['anomalies']?['earlyCount'] ?? 0} · 缺卡 ${a['anomalies']?['missingCount'] ?? 0}',
            ),
            OutlinedButton(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      final me =
                          (await widget.api.call('/auth/me'))['user'] as Json;
                      if (!mounted || !context.mounted) return;
                      await go(
                        context,
                        AttendancePage(api: widget.api, user: me),
                      );
                    }),
              child: const Text('查看本月考勤明细'),
            ),
            section('考勤简单统计'),
            Text('正常打卡次数 ${a['normalPunches']}'),
            Text('出勤打卡次数 ${a['fieldPunches']}'),
            Text('有打卡记录天数（人日） ${a['recordedDays']}'),
            Text('已批准补卡次数 ${a['approvedCorrections']}'),
            const Text('以下无记录为0天，仅计已批准且未撤销的区间内自然日与半天（含周末）。'),
            Text(
              '请假 ${a['leaveDays']} 天 · 出差 ${a['travelDays']} 天 · 外出 ${a['outingDays']} 天',
            ),
            Text(
              '加班 ${a['overtimeDays']} 天 · 折算调休 ${a['overtimeCreditedDays']} 天',
            ),
            OutlinedButton(
              onPressed: busy ? null : loadMembers,
              child: const Text('按员工查看打卡记录'),
            ),
            for (final member in members)
              ListTile(
                title: Text('${member['name']}（${member['employee_no']}）'),
                trailing: IconButton(
                  tooltip: '成员月度考勤',
                  icon: const Icon(Icons.calendar_month),
                  onPressed: () => run(() async {
                    final me =
                        (await widget.api.call('/auth/me'))['user'] as Json;
                    if (!mounted || !context.mounted) return;
                    await go(
                      context,
                      AttendancePage(
                        api: widget.api,
                        user: me,
                        memberId: member['id'] as int,
                      ),
                    );
                  }),
                ),
                onTap: () => go(
                  context,
                  WorkbenchPunches(
                    api: widget.api,
                    user: member,
                    from: d['from'],
                    to: d['to'],
                  ),
                ),
              ),
            if (members.isNotEmpty || memberOffset > 0)
              Wrap(
                children: [
                  TextButton(
                    onPressed: busy || memberOffset == 0
                        ? null
                        : () {
                            memberOffset -= 200;
                            loadMembers();
                          },
                    child: const Text('上一页员工'),
                  ),
                  TextButton(
                    onPressed: busy || members.length < 200
                        ? null
                        : () {
                            memberOffset += 200;
                            loadMembers();
                          },
                    child: const Text('下一页员工'),
                  ),
                ],
              ),
            for (final r in a['daysByType'])
              Text(
                '${workbenchKinds[r['kind']]} ${r['leaveType']}：${r['days']} 天${r['kind'] == 'OVERTIME' ? '，调休额度 ${r['creditedDays']} 天' : ''}',
              ),
            section('审批时效'),
            Text('当前待我审批 ${p['pendingForMe']}'),
            Text(
              '${d['scope'] == 'self' ? '我发起尚未办结' : '范围内发起尚未办结'} ${p['initiatedPending']}',
            ),
            Text('统计期办结 ${p['completed']} · 平均审批耗时 ${p['averageHours']} 小时'),
            Text('当前待审批 ${p['waiting']['total']}'),
            Text(
              '1 天内 ${p['waiting']['withinOneDay']} · 1–3 天 ${p['waiting']['oneToThreeDays']} · 超过 3 天 ${p['waiting']['overThreeDays']}',
            ),
            for (final r in p['byType'])
              Text(
                '${workbenchKinds[r['kind']]} · ${r['status'] == 'APPROVED' ? '通过' : '驳回'}：${r['completed']} 单 · 平均 ${r['averageHours']} 小时',
              ),
            const Text(
              '待审批和等待分段为实时全部待审，不受日期限制。本人统计本人发起及待本人审批的申请；办结含本人参与审批的申请。团队按申请人范围统计。仅六类审批，不含会议、任务或文档。打卡含批准补卡且不重复计数；可在打卡记录和消息中心查看本人记录。加班兑换使用单据保存的比例。',
            ),
          ],
        ],
      ),
    );
  }
}

class WorkbenchPunches extends StatefulWidget {
  final Api api;
  final Json user;
  final String from, to;
  const WorkbenchPunches({
    super.key,
    required this.api,
    required this.user,
    required this.from,
    required this.to,
  });
  @override
  State<WorkbenchPunches> createState() => _WorkbenchPunchesState();
}

class _WorkbenchPunchesState extends State<WorkbenchPunches>
    with Guard<WorkbenchPunches> {
  List<Json> rows = [];
  int offset = 0;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() => run(() async {
    final result = records(
      await widget.api.call(
        '/punches?${Uri(queryParameters: {'userId': '${widget.user['id']}', 'from': widget.from, 'to': widget.to, 'offset': '$offset'}).query}',
      ),
    );
    if (mounted) setState(() => rows = result);
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text('${widget.user['name']}的打卡记录')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        feedback,
        Text('${widget.from} 至 ${widget.to} · 北京时间 · 每页最多200条'),
        for (final r in rows)
          ListTile(
            title: Text(timeOf(r['punched_at'])),
            subtitle: Text(
              '${textOf(r['kind'])} · ${r['correction_request_id'] == null ? '现场打卡' : '补卡'}',
            ),
          ),
        if (rows.isEmpty) const Text('暂无记录'),
        Wrap(
          children: [
            TextButton(
              onPressed: busy || offset == 0
                  ? null
                  : () {
                      offset -= 200;
                      load();
                    },
              child: const Text('上一页记录'),
            ),
            TextButton(
              onPressed: busy || rows.length < 200
                  ? null
                  : () {
                      offset += 200;
                      load();
                    },
              child: const Text('下一页记录'),
            ),
          ],
        ),
      ],
    ),
  );
}
