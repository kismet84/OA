import 'package:flutter/material.dart';

import 'theme.dart';

import 'api.dart';
import 'ui.dart';

const taskStates = {
  'TODO': OaTokens.statusTodoLabel,
  'IN_PROGRESS': OaTokens.statusInProgressLabel,
  'COMPLETED': OaTokens.statusCompletedLabel,
  'CANCELLED': '已取消',
};

class TasksPage extends StatefulWidget {
  final Api api;
  final Json user;
  const TasksPage({super.key, required this.api, required this.user});
  @override
  State<TasksPage> createState() => _TasksState();
}

class _TasksState extends State<TasksPage> with Guard<TasksPage> {
  String scope = 'assigned', status = '';
  int offset = 0;
  List<Json> rows = [], people = [];
  int? creatorId, assigneeId;
  final from = TextEditingController(), to = TextEditingController();
  @override
  void initState() {
    super.initState();
    run(() async {
      people = [widget.user, ...records(await widget.api.call('/colleagues'))];
      await fetch();
    });
  }

  @override
  void dispose() {
    from.dispose();
    to.dispose();
    super.dispose();
  }

  Future<void> fetch() async {
    final q = {
      'scope': scope,
      'offset': '$offset',
      if (status.isNotEmpty) 'status': status,
      if (from.text.isNotEmpty) 'dueFrom': from.text,
      if (to.text.isNotEmpty) 'dueTo': to.text,
      if (creatorId != null) 'creatorId': '$creatorId',
      if (assigneeId != null) 'assigneeId': '$assigneeId',
    };
    final data = records(
      await widget.api.call('/tasks?${Uri(queryParameters: q).query}'),
    );
    if (mounted) setState(() => rows = data);
  }

  Future<void> open([int? id]) async {
    await go(context, TaskPage(api: widget.api, user: widget.user, id: id));
    if (mounted && widget.api.token != null) await run(fetch);
  }

  Widget personFilter(String label, int? value, ValueChanged<int?> change) =>
      Padding(
        padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
        child: DropdownButtonFormField<int>(
          initialValue: value,
          decoration: InputDecoration(labelText: label),
          isExpanded: true,
          items: [
            const DropdownMenuItem<int>(value: null, child: Text('全部')),
            for (final p in people)
              DropdownMenuItem(
                value: p['id'] as int,
                child: Text('${p['name']}'),
              ),
          ],
          onChanged: busy ? null : change,
        ),
      );
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('待办任务')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space16),
      children: [
        const Text('任务指派与跟进，无需审批。截止日期按北京时间，仅作提醒。'),
        feedback,
        Padding(
          padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
          child: DropdownButtonFormField<String>(
            initialValue: scope,
            decoration: const InputDecoration(labelText: '任务范围'),
            items: const [
              DropdownMenuItem(value: 'assigned', child: Text('我负责的')),
              DropdownMenuItem(value: 'initiated', child: Text('我发起的（未取消）')),
              DropdownMenuItem(value: '', child: Text('我有权查看的任务')),
            ],
            onChanged: busy ? null : (v) => setState(() => scope = v!),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
          child: DropdownButtonFormField<String>(
            initialValue: status,
            decoration: const InputDecoration(labelText: '任务状态'),
            items: [
              const DropdownMenuItem(value: '', child: Text('全部状态')),
              for (final s in taskStates.entries)
                DropdownMenuItem(value: s.key, child: Text(s.value)),
            ],
            onChanged: busy ? null : (v) => setState(() => status = v!),
          ),
        ),
        field(from, '截止起日（YYYY-MM-DD）'),
        field(to, '截止止日（YYYY-MM-DD）'),
        personFilter('发起人', creatorId, (v) => setState(() => creatorId = v)),
        personFilter('负责人', assigneeId, (v) => setState(() => assigneeId = v)),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  offset = 0;
                  run(fetch);
                },
          child: const Text('查询任务'),
        ),
        OutlinedButton(
          onPressed: busy ? null : () => open(),
          child: const Text('创建任务'),
        ),
        for (final r in rows)
          Card(
            child: ListTile(
              title: Text('${r['title']}'),
              leading: r['status'] == null
                  ? null
                  : StatusChip(r['status'], label: taskStates[r['status']]),
              subtitle: Text(
                '${r['creator_name']}\n截止：${timeOf(r['due_at'])}${r['overdue'] == true ? '（已过期）' : ''}',
              ),
              onTap: () => open(r['id'] as int),
            ),
          ),
        if (rows.isEmpty && !busy) const Text('暂无任务'),
        Text('第 ${offset ~/ 200 + 1} 页 · 每页最多200条'),
        Row(
          children: [
            TextButton(
              onPressed: busy || offset == 0
                  ? null
                  : () {
                      offset -= 200;
                      run(fetch);
                    },
              child: const Text('上一页'),
            ),
            TextButton(
              onPressed: busy || rows.length < 200
                  ? null
                  : () {
                      offset += 200;
                      run(fetch);
                    },
              child: const Text('下一页'),
            ),
          ],
        ),
      ],
    ),
  );
}

class TaskPage extends StatefulWidget {
  final Api api;
  final Json user;
  final int? id;
  const TaskPage({super.key, required this.api, required this.user, this.id});
  @override
  State<TaskPage> createState() => _TaskState();
}

class _TaskState extends State<TaskPage> with Guard<TaskPage> {
  int? id;
  Json? detail;
  bool editing = false;
  List<Json> people = [];
  Set<int> assigned = {};
  final title = TextEditingController(),
      description = TextEditingController(),
      due = TextEditingController(),
      content = TextEditingController();
  @override
  void initState() {
    super.initState();
    id = widget.id;
    run(() async {
      if (id == null) {
        await prepare();
      } else {
        await load();
      }
    });
  }

  @override
  void dispose() {
    for (final c in [title, description, due, content]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> load() async {
    final d = await widget.api.call('/tasks/$id') as Json;
    if (mounted) {
      setState(() {
        detail = d;
        editing = false;
        content.clear();
      });
    }
  }

  Future<void> prepare() async {
    final colleagues = records(await widget.api.call('/colleagues'));
    if (!mounted) return;
    setState(() {
      people = [
        {...widget.user, 'employee_no': '本人'},
        ...colleagues,
      ];
      for (final p in records(detail?['assignees'] ?? [])) {
        if (!people.any((u) => u['id'] == p['user_id'])) {
          people.add({
            'id': p['user_id'],
            'name': '${p['name']}（已停用，请移出）',
            'employee_no': p['employee_no'],
          });
        }
      }
      title.text = detail?['title'] ?? '';
      description.text = detail?['description'] ?? '';
      due.text = detail?['due_at'] ?? '';
      assigned = records(detail?['assignees'] ?? [])
          .map((p) => p['user_id'] as int)
          .toSet();
      editing = true;
    });
  }

  Future<void> save() => run(() async {
    if (title.text.trim().isEmpty) {
      throw ApiError('请填写任务标题');
    }
    if (assigned.isEmpty || assigned.length > 200) {
      throw ApiError('请选择1至200名负责人');
    }
    final r = await widget.api.call(
      id == null ? '/tasks' : '/tasks/$id',
      method: id == null ? 'POST' : 'PUT',
      data: {
        'title': title.text,
        'description': description.text,
        'dueAt': due.text,
        'assigneeIds': assigned.toList(),
      },
    ) as Json;
    id = r['id'];
    await load();
  }, success: '任务已保存');
  Future<void> state(String status) => run(() async {
    await widget.api.call(
      '/tasks/$id/status',
      method: 'POST',
      data: {'status': status, 'content': content.text},
    );
    await load();
  }, success: '状态已更新');
  Future<void> follow() => run(() async {
    await widget.api.call(
      '/tasks/$id/followups',
      method: 'POST',
      data: {'content': content.text},
    );
    await load();
  }, success: '跟进已追加');
  @override
  Widget build(BuildContext context) {
    final d = detail;
    final active = d != null && ['TODO', 'IN_PROGRESS'].contains(d['status']);
    return Scaffold(
      appBar: AppBar(title: Text(editing ? '编辑任务' : '任务详情')),
      body: ListView(
        padding: const EdgeInsets.all(OaTokens.space16),
        children: [
          const Text('业务时间：北京时间（Asia/Shanghai）'),
          feedback,
          if (editing) ...[
            field(title, '任务标题', max: 200),
            field(description, '任务说明', lines: 3, max: 5000),
            if (d?['status'] == 'COMPLETED')
              Text('已完成任务的负责人及截止时间已锁定；需要调整请先重开。截止：${timeOf(d?['due_at'])}')
            else
              field(due, '截止日期或时间（YYYY-MM-DD 或 YYYY-MM-DDTHH:mm，可选）'),
            const Text('负责人（至少1人，最多200人）'),
            for (final p in people)
              CheckboxListTile(
                title: Text('${p['name']} · ${p['employee_no'] ?? ''}'),
                value: assigned.contains(p['id']),
                onChanged: busy || d?['status'] == 'COMPLETED'
                    ? null
                    : (v) => setState(() {
                        if (v == true) {
                          assigned.add(p['id'] as int);
                        } else {
                          assigned.remove(p['id']);
                        }
                      }),
              ),
            FilledButton(
              onPressed: busy ? null : save,
              child: const Text('保存任务'),
            ),
            if (d != null)
              TextButton(
                onPressed: () => setState(() => editing = false),
                child: const Text('返回详情'),
              ),
          ] else if (d != null) ...[
            Text(
              '${d['title']}',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            StatusChip(d['status'], label: taskStates[d['status']]),
            Text('发起人：${d['creator_name']}'),
            Text(
              '截止：${timeOf(d['due_at'])}${d['overdue'] == true ? '（已过期）' : ''}',
            ),
            Text('${d['description'] ?? '未填写说明'}'),
            Text(
              '负责人：${records(d['assignees']).map((p) => p['name']).join('、')}',
            ),
            section('跟进记录'),
            if (records(d['followups']).isEmpty) const Text('暂无跟进'),
            for (final f in records(d['followups']))
              ListTile(
                title: Text('${f['content']}'),
                subtitle: Text(
                  '${f['user_name']} · ${timeOf(f['created_at'])}',
                ),
              ),
            if (d['status'] != 'CANCELLED') ...[
              field(content, '跟进说明（完成时建议填写）', lines: 3, max: 5000),
              OutlinedButton(
                onPressed: busy ? null : follow,
                child: const Text('追加跟进'),
              ),
            ],
            if (active) ...[
              OutlinedButton(
                onPressed: busy
                    ? null
                    : () =>
                          state(d['status'] == 'TODO' ? 'IN_PROGRESS' : 'TODO'),
                child: Text(d['status'] == 'TODO' ? '开始处理' : '改为待处理'),
              ),
              FilledButton(
                onPressed: busy ? null : () => state('COMPLETED'),
                child: const Text('标记已完成'),
              ),
            ],
            if (d['canEdit'] == true && d['status'] != 'CANCELLED') ...[
              OutlinedButton(
                onPressed: busy ? null : () => run(prepare),
                child: const Text('修改任务'),
              ),
              TextButton(
                onPressed: busy ? null : () => state('CANCELLED'),
                child: const Text('取消任务'),
              ),
            ],
            if (d['canRestore'] == true &&
                ['COMPLETED', 'CANCELLED'].contains(d['status']))
              OutlinedButton(
                onPressed: busy
                    ? null
                    : () => state(
                        d['status'] == 'CANCELLED' ? 'TODO' : 'IN_PROGRESS',
                      ),
                child: Text(d['status'] == 'CANCELLED' ? '恢复为待处理' : '重开为进行中'),
              ),
          ],
        ],
      ),
    );
  }
}
