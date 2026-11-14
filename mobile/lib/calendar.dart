import 'package:flutter/material.dart';

import 'theme.dart';

import 'api.dart';
import 'ui.dart';

String shanghaiDay() => DateTime.now()
    .toUtc()
    .add(const Duration(hours: 8))
    .toIso8601String()
    .substring(0, 10);
String nextCalendarDay(String date) =>
    DateTime.parse('${date}T00:00:00Z')
        .add(const Duration(days: 1))
        .toIso8601String()
        .substring(0, 10);
const meetingResponses = {'PENDING': '待回复', 'ACCEPTED': '接受', 'DECLINED': '请假'};

class CalendarPage extends StatefulWidget {
  final Api api;
  final Json user;
  const CalendarPage({super.key, required this.api, required this.user});
  @override
  State<CalendarPage> createState() => _CalendarState();
}

class _CalendarState extends State<CalendarPage> with Guard<CalendarPage> {
  final from = TextEditingController(text: shanghaiDay()),
      to = TextEditingController(text: shanghaiDay());
  List<Json> rows = [];
  bool mine = true, cancelled = false;
  int offset = 0;
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
        'from': '${from.text}T00:00:00',
        'to': '${nextCalendarDay(to.text)}T00:00:00',
        'mine': '$mine',
        'includeCancelled': '$cancelled',
        'offset': '$offset',
      },
    ).query;
    final result = records(await widget.api.call('/meetings?$q'));
    if (mounted) setState(() => rows = result);
  });
  Future<void> open([int? id]) async {
    await go(context, MeetingPage(api: widget.api, user: widget.user, id: id));
    if (mounted && widget.api.token != null) load();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('日程与会议')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space16),
      children: [
        const Text('北京时间（Asia/Shanghai）· 会议室即时预定，无需审批'),
        feedback,
        field(from, '起始日期（YYYY-MM-DD）'),
        field(to, '截止日期（YYYY-MM-DD）'),
        SwitchListTile(
          title: const Text('只看我的日程'),
          value: mine,
          onChanged: busy ? null : (v) => setState(() => mine = v),
        ),
        SwitchListTile(
          title: const Text('含已取消'),
          value: cancelled,
          onChanged: busy ? null : (v) => setState(() => cancelled = v),
        ),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  offset = 0;
                  load();
                },
          child: const Text('查询会议'),
        ),
        OutlinedButton(
          onPressed: busy ? null : () => open(),
          child: const Text('创建会议 / 预定会议室'),
        ),
        for (final row in rows)
          Card(
            child: ListTile(
              title: Text(
                '${row['title']}${row['cancelled_at'] == null ? '' : '（已取消）'}',
              ),
              subtitle: Text(
                '${timeOf(row['start_at'])} — ${timeOf(row['end_at'])}\n${row['room_name'] ?? '未订会议室'} · ${row['organizer_name']}',
              ),
              onTap: busy ? null : () => open(row['id'] as int),
            ),
          ),
        if (rows.isEmpty && !busy) const Text('当前日期范围没有会议'),
        Text('第 ${offset ~/ 200 + 1} 页 · 每页最多200条'),
        Row(
          children: [
            TextButton(
              onPressed: busy || offset == 0
                  ? null
                  : () {
                      offset -= 200;
                      load();
                    },
              child: const Text('上一页'),
            ),
            TextButton(
              onPressed: busy || rows.length < 200
                  ? null
                  : () {
                      offset += 200;
                      load();
                    },
              child: const Text('下一页'),
            ),
          ],
        ),
      ],
    ),
  );
}

class MeetingPage extends StatefulWidget {
  final Api api;
  final Json user;
  final int? id;
  const MeetingPage({
    super.key,
    required this.api,
    required this.user,
    this.id,
  });
  @override
  State<MeetingPage> createState() => _MeetingState();
}

class _MeetingState extends State<MeetingPage> with Guard<MeetingPage> {
  Json? detail;
  int? id, roomId;
  bool editing = false;
  List<Json> rooms = [], people = [], occupancy = [];
  List<String> warnings = [];
  Set<int> invited = {};
  final title = TextEditingController(),
      start = TextEditingController(),
      end = TextEditingController(),
      description = TextEditingController(),
      reason = TextEditingController();
  @override
  void initState() {
    super.initState();
    id = widget.id;
    run(() async {
      if (id != null) {
        await load();
      } else {
        await prepare();
      }
    });
  }

  @override
  void dispose() {
    for (final c in [title, start, end, description, reason]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> load() async {
    final result = await widget.api.call('/meetings/$id') as Json;
    if (mounted) {
      setState(() {
        detail = result;
        editing = false;
      });
    }
  }

  Future<void> prepare() async {
    final all = <Json>[];
    for (int offset = 0; ; offset += 200) {
      final part = records(
        await widget.api.call('/meeting-rooms?offset=$offset'),
      );
      all.addAll(part);
      if (part.length < 200) break;
    }
    final colleagues = records(await widget.api.call('/colleagues'));
    if (!mounted) return;
    setState(() {
      rooms = all;
      people = [
        {...widget.user, 'employee_no': '本人'},
        ...colleagues,
      ];
      title.text = detail?['title'] ?? '';
      start.text = detail?['start_at'] ?? '${shanghaiDay()}T09:00';
      end.text = detail?['end_at'] ?? '${shanghaiDay()}T10:00';
      roomId = detail?['room_id'] as int?;
      description.text = detail?['description'] ?? '';
      invited = detail == null
          ? <int>{}
          : records(detail!['attendees'])
                .where((p) => people.any((u) => u['id'] == p['user_id']))
                .map((p) => p['user_id'] as int)
                .toSet();
      if (detail != null &&
          records(detail!['attendees'])
              .any((p) => !people.any((u) => u['id'] == p['user_id']))) {
        warnings = ['已停用参会人将在保存时移出名单'];
      }
      editing = true;
    });
    await occupied();
  }

  Future<void> occupied() async {
    if (roomId == null) {
      if (mounted) setState(() => occupancy = []);
      return;
    }
    if (start.text.length < 10 || end.text.length < 10) {
      throw ApiError('请填写明确的开始和结束时间');
    }
    final q = {
      'roomId': '$roomId',
      'from': '${start.text.substring(0, 10)}T00:00:00',
      'to': '${nextCalendarDay(end.text.substring(0, 10))}T00:00:00',
    };
    final all = <Json>[];
    for (int offset = 0; ; offset += 200) {
      q['offset'] = '$offset';
      final part = records(
        await widget.api.call('/meetings?${Uri(queryParameters: q).query}'),
      );
      all.addAll(part);
      if (part.length < 200) break;
    }
    if (mounted) setState(() => occupancy = all);
  }

  Future<void> save() => run(() async {
    final result = await widget.api.call(
      id == null ? '/meetings' : '/meetings/$id',
      method: id == null ? 'POST' : 'PUT',
      data: {
        'title': title.text,
        'startAt': start.text,
        'endAt': end.text,
        'roomId': roomId,
        'description': description.text,
        'attendeeIds': invited.toList(),
      },
    ) as Json;
    id = result['id'];
    warnings = (result['warnings'] as List).cast<String>();
    await load();
  }, success: '会议已保存');
  Future<void> reply(String response) => run(() async {
    await widget.api.call(
      '/meetings/$id/reply',
      method: 'POST',
      data: {'response': response, 'reason': reason.text},
    );
    await load();
  }, success: '参会回复已保存');
  Future<void> cancel() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('取消会议？'),
        content: const Text('将通知参会人并立即释放会议室。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('返回'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('确认取消'),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      await run(() async {
        await widget.api.call('/meetings/$id/cancel', method: 'POST');
        await load();
      }, success: '会议已取消');
    }
  }

  @override
  Widget build(BuildContext context) {
    final d = detail;
    return Scaffold(
      appBar: AppBar(title: Text(editing ? '编辑会议' : '会议详情')),
      body: ListView(
        padding: const EdgeInsets.all(OaTokens.space16),
        children: [
          const Text('业务时间：北京时间（Asia/Shanghai）'),
          feedback,
          for (final w in warnings) Text('提示：$w'),
          if (editing) ...[
            field(title, '会议标题', max: 200),
            field(start, '开始时间（YYYY-MM-DDTHH:mm）'),
            field(end, '结束时间（YYYY-MM-DDTHH:mm）'),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
              child: DropdownButtonFormField<int>(
                initialValue: roomId,
                decoration: const InputDecoration(labelText: '预定会议室'),
                isExpanded: true,
                items: [
                  const DropdownMenuItem<int>(
                    value: null,
                    child: Text('不订会议室（仅日程）'),
                  ),
                  for (final r in rooms)
                    DropdownMenuItem(
                      value: r['id'] as int,
                      child: Text(
                        '${r['name']} · ${r['location'] ?? '未填地点'} · ${r['capacity'] ?? '不限'}人',
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  if (roomId != null && !rooms.any((r) => r['id'] == roomId))
                    DropdownMenuItem(
                      value: roomId,
                      child: Text('${detail?['room_name']}（已停用，仅保留原预定）'),
                    ),
                ],
                onChanged: busy
                    ? null
                    : (v) {
                        setState(() => roomId = v);
                        run(occupied);
                      },
              ),
            ),
            OutlinedButton(
              onPressed: busy ? null : () => run(occupied),
              child: const Text('查看所选日期会议室占用'),
            ),
            for (final o in occupancy)
              Text(
                '${timeOf(o['start_at'])} — ${timeOf(o['end_at'])} · ${o['title']} · ${o['organizer_name']}',
              ),
            if (roomId != null && occupancy.isEmpty) const Text('当前日期没有占用'),
            field(description, '说明', lines: 3, max: 2000),
            const Text('邀请同事（最多200人，组织者自动参加）'),
            for (final p in people.where(
              (p) => p['id'] != (detail?['organizer_id'] ?? widget.user['id']),
            ))
              CheckboxListTile(
                title: Text('${p['name']} · ${p['employee_no']}'),
                value: invited.contains(p['id']),
                onChanged: busy
                    ? null
                    : (v) => setState(() {
                        if (v == true) {
                          invited.add(p['id'] as int);
                        } else {
                          invited.remove(p['id']);
                        }
                      }),
              ),
            FilledButton(
              onPressed: busy ? null : save,
              child: const Text('保存会议'),
            ),
            if (d != null)
              TextButton(
                onPressed: busy ? null : () => setState(() => editing = false),
                child: const Text('返回详情'),
              ),
          ] else if (d != null) ...[
            Text(
              '${d['title']}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            Text('${timeOf(d['start_at'])} — ${timeOf(d['end_at'])}'),
            Text(
              '会议室：${d['room_name'] ?? '未订会议室'} · 组织者：${d['organizer_name']}',
            ),
            Text(d['cancelled_at'] == null ? '进行中 / 待开始' : '已取消'),
            if (d.containsKey('description'))
              Text('说明：${d['description'] ?? '未填写'}'),
            if (d['attendees'] != null) ...[
              Text('参会人（含组织者 ${d['organizer_name']}）'),
              for (final p in records(d['attendees']))
                Text(
                  '${p['name']} · ${p['employee_no']} ${meetingResponses[p['response']] ?? ''} ${p['reason'] ?? ''}',
                ),
            ] else
              const Text('仅参会人、组织者和管理员可查看说明及完整名单。'),
            if (d['canReply'] == true) ...[
              field(reason, '请假原因（可选）', max: 300),
              FilledButton(
                onPressed: busy ? null : () => reply('ACCEPTED'),
                child: const Text('接受邀请'),
              ),
              OutlinedButton(
                onPressed: busy ? null : () => reply('DECLINED'),
                child: const Text('请假'),
              ),
            ],
            if (d['canEdit'] == true && d['cancelled_at'] == null) ...[
              OutlinedButton(
                onPressed: busy ? null : () => run(prepare),
                child: const Text('修改会议'),
              ),
              TextButton(
                onPressed: busy ? null : cancel,
                child: const Text('取消会议'),
              ),
            ],
          ],
        ],
      ),
    );
  }
}
