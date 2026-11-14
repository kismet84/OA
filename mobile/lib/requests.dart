import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import 'theme.dart';

import 'api.dart';
import 'urge_button.dart';
import 'ui.dart';
import 'validation.dart';

class ExpenseLine {
  final description = TextEditingController(), amount = TextEditingController();
  void dispose() {
    description.dispose();
    amount.dispose();
  }
}

class RequestFormPage extends StatefulWidget {
  final Api api;
  final String initialKind;
  final Json? initialDraft;
  const RequestFormPage({
    super.key,
    required this.api,
    this.initialKind = 'LEAVE',
    this.initialDraft,
  });
  @override
  State<RequestFormPage> createState() => _RequestFormState();
}

class _RequestFormState extends State<RequestFormPage>
    with Guard<RequestFormPage> {
  String kind = 'LEAVE', punchKind = 'NORMAL';
  DateTime correctionAt = shanghaiNow().subtract(const Duration(hours: 1));
  Json? policy;
  int? typeId;
  List<Json> types = [], colleagues = [];
  final reason = TextEditingController(), amount = TextEditingController();
  final lines = [ExpenseLine()];
  final files = <Json>[];
  final cc = <int>{};
  late DateTime start = DateTime(
        shanghaiNow().year,
        shanghaiNow().month,
        shanghaiNow().day + 1,
      ),
      end = start;
  String startTime = '09:00', endTime = '18:00';
  bool ready = false;
  List<Json> templates = [];
  String? draftNotice;
  bool startChosen = true, endChosen = true;
  @override
  void initState() {
    super.initState();
    kind = widget.initialKind;
    load();
  }

  Future<void> load() => run(() async {
    policy = await widget.api.call('/attendance-policy');
    types = records(await widget.api.call('/leave-types'));
    colleagues = records(await widget.api.call('/colleagues'));
    typeId = types.isEmpty ? null : types.first['id'];
    templates = records(await widget.api.call('/request-templates'));
    if (widget.initialDraft != null) {
      fillDraft(widget.initialDraft!);
    }
    ready = true;
  });
  void fillDraft(Json draft) {
    final p = draft['payload'] is Map
        ? Map<String, dynamic>.from(draft['payload'] as Map)
        : draft;
    kind = '${draft['kind'] ?? kind}';
    typeId = p['typeId'] as int?;
    reason.text = '${p['reason'] ?? ''}';
    amount.text = '${p['amount'] ?? ''}';
    startTime = '${p['startTime'] ?? '09:00'}';
    endTime = '${p['endTime'] ?? '18:00'}';
    if (p['correctionAt'] != null) {
      correctionAt = DateTime.parse('${p['correctionAt']}');
    }
    punchKind = '${p['punchKind'] ?? 'NORMAL'}';
    cc.clear();
    cc.addAll((p['ccUserIds'] as List? ?? []).cast<int>());
    for (final l in lines) {
      l.dispose();
    }
    lines.clear();
    for (final item in (p['items'] as List? ?? [])) {
      final l = ExpenseLine();
      l.description.text = '${item['description']}';
      l.amount.text = '${item['amount']}';
      lines.add(l);
    }
    if (lines.isEmpty) lines.add(ExpenseLine());
    files.clear();
    startChosen = false;
    endChosen = false;
    final warnings = (draft['warnings'] as List? ?? [])
        .where((w) => w != '请重新选择日期，附件不会复制')
        .join('；');
    draftNotice = '请重新选择日期，附件不会复制${warnings.isEmpty ? '' : '；$warnings'}';
  }

  Future<String?> templateName([String initial = '']) async {
    String edited = initial;
    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('模板名称'),
        content: TextFormField(
          initialValue: initial,
          onChanged: (value) => edited = value,
          maxLength: 60,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, edited.trim()),
            child: const Text('保存'),
          ),
        ],
      ),
    );
    return result;
  }

  Future<void> saveTemplate() => run(() async {
    final name = await templateName();
    if (name == null) return;
    if (name.isEmpty) throw ApiError('请填写模板名称');
    await widget.api.call(
      '/request-templates',
      method: 'POST',
      data: {
        'name': name,
        'kind': kind,
        'payload': {
          'typeId': typeId,
          'reason': reason.text,
          'amount': double.tryParse(amount.text) ?? 0,
          'items': [
            for (final l in lines)
              {
                'description': l.description.text,
                'amount': double.tryParse(l.amount.text) ?? 0,
              },
          ],
          'ccUserIds': cc.toList(),
          'startTime': startTime,
          'endTime': endTime,
          'durationDays':
              end.difference(start).inDays +
              (endTime == '18:00' ? 1 : 0.5) -
              (startTime == '13:00' ? 0.5 : 0),
        },
      },
    );
    templates = records(await widget.api.call('/request-templates'));
  }, success: '模板已保存');
  Future<void> chooseTemplate() async {
    final selected = await showModalBottomSheet<Json>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            const ListTile(title: Text('从模板填单')),
            if (templates.isEmpty) const ListTile(title: Text('暂无常用模板')),
            for (final t in templates)
              ListTile(
                title: Text('${t['name']}（${textOf(t['kind'])}）'),
                onTap: () => Navigator.pop(ctx, t),
              ),
          ],
        ),
      ),
    );
    if (selected != null && mounted) setState(() => fillDraft(selected));
  }

  Future<void> manageTemplates() async {
    final selected = await showModalBottomSheet<Json>(
      context: context,
      builder: (ctx) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            const ListTile(title: Text('选择要管理的模板')),
            for (final t in templates)
              ListTile(
                title: Text('${t['name']}'),
                onTap: () => Navigator.pop(ctx, t),
              ),
          ],
        ),
      ),
    );
    if (selected == null || !mounted) return;
    final action = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('${selected['name']}'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, 'rename'),
            child: const Text('重命名'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, 'delete'),
            child: const Text('删除模板'),
          ),
        ],
      ),
    );
    if (action == null || !mounted) return;
    String? name;
    if (action == 'rename') {
      name = await templateName('${selected['name']}');
      if (name == null) return;
    }
    await run(() async {
      await widget.api.call(
        '/request-templates/${selected['id']}',
        method: action == 'delete' ? 'DELETE' : 'PUT',
        data: action == 'delete'
            ? null
            : {
                'name': name,
                'kind': selected['kind'],
                'payload': selected['payload'],
              },
      );
      templates = records(await widget.api.call('/request-templates'));
    });
  }

  @override
  void dispose() {
    reason.dispose();
    amount.dispose();
    for (final l in lines) {
      l.dispose();
    }
    super.dispose();
  }

  String date(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  Future<void> pickDate(bool first) async {
    final d = await showDatePicker(
      context: context,
      initialDate: first ? start : end,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
    );
    if (d != null && mounted) {
      setState(() {
        if (first) {
          start = d;
          startChosen = true;
        } else {
          end = d;
          endChosen = true;
        }
      });
    }
  }

  Future<void> pickCorrection() async {
    final d = await showDatePicker(
      context: context,
      initialDate: correctionAt,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
    );
    if (d == null || !mounted) return;
    final t = await showTimePicker(
      context: context,
      initialTime: TimeOfDay.fromDateTime(correctionAt),
    );
    if (t != null && mounted) {
      setState(
        () => correctionAt = DateTime(d.year, d.month, d.day, t.hour, t.minute),
      );
    }
  }

  Future<void> pickFiles() => run(() async {
    final List<PlatformFile> result;
    if (kind == 'EXPENSE') {
      result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['pdf', 'png', 'jpg', 'jpeg'],
      );
    } else {
      final file = await FilePicker.pickFile(
        type: FileType.custom,
        allowedExtensions: ['pdf', 'png', 'jpg', 'jpeg'],
      );
      result = file == null ? [] : [file];
    }
    if (result.isEmpty) return;
    if (kind == 'EXPENSE' && files.length + result.length > 30) {
      throw ApiError('票据最多30张');
    }
    for (final (i, f) in result.indexed) {
      final size = await f.length();
      if (size == null || size == 0) {
        throw ApiError('每份附件须为1字节至10MB');
      }
      final bytes = await f.readAsBytes();
      final id = await widget.api.upload(
        f.name,
        bytes,
        index: i + 1,
        total: result.length,
      );
      if (!mounted) return;
      setState(() {
        if (kind != 'EXPENSE') files.clear();
        files.add({'id': id, 'name': f.name});
      });
    }
  });
  Future<void> pickCc() async {
    final selected = Set<int>.from(cc);
    final accepted = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, update) => AlertDialog(
          title: const Text('选择抄送同事'),
          content: SizedBox(
            width: 400,
            height: 360,
            child: colleagues.isEmpty
                ? empty('暂无可选同事')
                : ListView(
                    children: [
                      for (final u in colleagues)
                        CheckboxListTile(
                          value: selected.contains(u['id']),
                          title: Text('${u['name']}（${u['employee_no']}）'),
                          subtitle: Text('${u['department_name']}'),
                          onChanged: (v) => update(() {
                            if (v == true) {
                              selected.add(u['id']);
                            } else {
                              selected.remove(u['id']);
                            }
                          }),
                        ),
                    ],
                  ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('完成'),
            ),
          ],
        ),
      ),
    );
    if (accepted == true && mounted) {
      setState(() {
        cc.clear();
        cc.addAll(selected);
      });
    }
  }

  Future<void> submit() => run(() async {
    if (!['EXPENSE', 'CORRECTION'].contains(kind) &&
        (!startChosen || !endChosen)) {
      throw ApiError('请重新选择开始和结束日期');
    }
    final type = types.where((t) => t['id'] == typeId).firstOrNull;
    final payload = requestPayload(
      kind: kind,
      reason: reason.text,
      typeId: typeId,
      startAt: kind == 'CORRECTION'
          ? correctionAt.toIso8601String().replaceAll('Z', '')
          : '${date(start)}T$startTime:00',
      punchKind: punchKind,
      endAt: '${date(end)}T$endTime:00',
      amount: amount.text,
      items: lines
          .map(
            (l) => <String, dynamic>{
              'description': l.description.text,
              'amount': l.amount.text,
            },
          )
          .toList(),
      attachmentIds: files.map((f) => f['id'] as String).toList(),
      attachmentId: files.isEmpty ? null : files.first['id'],
      cc: cc.toList(),
      proofRequired: kind == 'LEAVE' && type?['proof_required'] == true,
    );
    await widget.api.call('/requests', method: 'POST', data: payload);
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('申请已提交')));
      Navigator.pop(context);
    }
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('提交申请')),
    body: AbsorbPointer(
      absorbing: busy,
      child: ListView(
        padding: const EdgeInsets.all(OaTokens.space20),
        children: [
          feedback,
          if (draftNotice != null)
            Text(
              draftNotice!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton(
                onPressed: busy ? null : chooseTemplate,
                child: const Text('从模板填单'),
              ),
              OutlinedButton(
                onPressed:
                    busy ||
                        !['LEAVE', 'TRAVEL', 'OUTING', 'EXPENSE'].contains(kind)
                    ? null
                    : saveTemplate,
                child: const Text('保存为常用模板'),
              ),
              TextButton(
                onPressed: busy ? null : manageTemplates,
                child: const Text('模板管理'),
              ),
            ],
          ),
          if (!ready && !busy)
            TextButton(onPressed: load, child: const Text('重新加载假期与同事')),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
            child: DropdownButtonFormField<String>(
              initialValue: kind,
              key: ValueKey(kind),
              decoration: const InputDecoration(labelText: '申请类型'),
              items: [
                for (final k in [
                  'LEAVE',
                  'TRAVEL',
                  'OUTING',
                  'EXPENSE',
                  'OVERTIME',
                  'CORRECTION',
                ])
                  DropdownMenuItem(value: k, child: Text(textOf(k))),
              ],
              onChanged: (v) => setState(() {
                kind = v!;
                files.clear();
              }),
            ),
          ),
          if (kind == 'LEAVE') ...[
            Padding(
              padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
              child: DropdownButtonFormField<int>(
                key: ValueKey(typeId),
                initialValue: typeId,
                decoration: const InputDecoration(labelText: '假期类型'),
                items: [
                  for (final t in types)
                    DropdownMenuItem<int>(
                      value: t['id'],
                      child: Text(
                        '${t['name']}${t['proof_required'] == true ? '（必须证明）' : ''}',
                      ),
                    ),
                ],
                onChanged: (v) => setState(() => typeId = v),
              ),
            ),
            if (types.isEmpty && ready) const Text('暂无启用的假期类型，请联系管理员'),
          ],
          if (kind == 'CORRECTION') ...[
            section('补卡 · 北京时间'),
            Padding(
              padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
              child: DropdownButtonFormField<String>(
                initialValue: punchKind,
                decoration: const InputDecoration(labelText: '补卡类型'),
                items: [
                  for (final k in ['NORMAL', 'FIELD'])
                    DropdownMenuItem(value: k, child: Text(textOf(k))),
                ],
                onChanged: (v) => setState(() => punchKind = v!),
              ),
            ),
            OutlinedButton(
              onPressed: pickCorrection,
              child: Text('补卡时间 ${timeOf(correctionAt.toIso8601String())}'),
            ),
            Text(
              '补卡日期结束后 ${policy?['correction_days'] ?? 3} 个自然日内提交。外勤须有该时刻有效的已批准出差或外出。无需当前位置或历史网络；批准后不可撤销。',
            ),
          ] else if (kind != 'EXPENSE') ...[
            section('起止时段 · 北京时间'),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => pickDate(true),
                    child: Text('开始 ${date(start)}'),
                  ),
                ),
                const SizedBox(width: 12),
                DropdownButton<String>(
                  value: startTime,
                  items: [
                    for (final t in ['09:00', '13:00'])
                      DropdownMenuItem(value: t, child: Text(t)),
                  ],
                  onChanged: (v) => setState(() => startTime = v!),
                ),
              ],
            ),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () => pickDate(false),
                    child: Text('结束 ${date(end)}'),
                  ),
                ),
                const SizedBox(width: 12),
                DropdownButton<String>(
                  value: endTime,
                  items: [
                    for (final t in ['13:00', '18:00'])
                      DropdownMenuItem(value: t, child: Text(t)),
                  ],
                  onChanged: (v) => setState(() => endTime = v!),
                ),
              ],
            ),
            const Text('上午、下午各半天，按自然日含周末。请假、出差、外出跨年请拆单；天数由服务端计算。'),
            if (kind == 'OVERTIME')
              Text(
                '加班按 ${policy?['overtime_ratio'] ?? 1}:1 计入开始年度调休；沿用请假分流阈值 ${policy?['leave_threshold'] ?? 1} 天。允许过去时段，须有结束时间。',
              ),
          ] else ...[
            field(
              amount,
              '报销总额（元）',
              keyboard: const TextInputType.numberWithOptions(decimal: true),
            ),
            section('费用明细'),
            for (final (i, l) in lines.indexed)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(OaTokens.space12),
                  child: Column(
                    children: [
                      Text('第 ${i + 1} 行'),
                      field(l.description, '用途 / 类别', max: 200),
                      field(
                        l.amount,
                        '金额（元）',
                        keyboard: const TextInputType.numberWithOptions(
                          decimal: true,
                        ),
                      ),
                      if (lines.length > 1)
                        TextButton(
                          onPressed: () => setState(() {
                            lines.removeAt(i);
                            l.dispose();
                          }),
                          child: const Text('删除此行'),
                        ),
                    ],
                  ),
                ),
              ),
            OutlinedButton(
              onPressed: lines.length >= 100
                  ? null
                  : () => setState(() => lines.add(ExpenseLine())),
              child: const Text('添加费用明细'),
            ),
            const Text('最多100行；各行合计须等于报销总额，金额最多两位小数。'),
          ],
          field(reason, '申请理由', lines: 3, max: 2000),
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.group_outlined),
            title: Text('抄送同事（已选 ${cc.length} 人）'),
            subtitle: Text(
              colleagues
                  .where((u) => cc.contains(u['id']))
                  .map((u) => u['name'])
                  .join('、'),
            ),
            trailing: const Icon(Icons.chevron_right_outlined),
            onTap: pickCc,
          ),
          section(kind == 'EXPENSE' ? '票据（必填，可多张）' : '证明文件'),
          const Text('支持 PDF / PNG / JPEG，每份最多10MB。'),
          for (final f in files)
            ListTile(
              title: Text('${f['name']}'),
              trailing: IconButton(
                tooltip: '移除附件',
                icon: const Icon(Icons.close_outlined),
                onPressed: () => setState(() => files.remove(f)),
              ),
            ),
          OutlinedButton.icon(
            onPressed: pickFiles,
            icon: const Icon(Icons.upload_file_outlined),
            label: Text(kind == 'EXPENSE' ? '上传票据' : '上传或替换证明'),
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: !ready || busy ? null : submit,
            child: const Text('确认提交'),
          ),
        ],
      ),
    ),
  );
}

class RequestDetailPage extends StatefulWidget {
  final Api api;
  final int id;
  final Json user;
  const RequestDetailPage({
    super.key,
    required this.api,
    required this.id,
    required this.user,
  });
  @override
  State<RequestDetailPage> createState() => _DetailState();
}

class _DetailState extends State<RequestDetailPage>
    with Guard<RequestDetailPage> {
  Json? detail;
  final comment = TextEditingController();
  bool approve = true;
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    comment.dispose();
    super.dispose();
  }

  Future<void> load() => run(() async {
    detail = await widget.api.call('/requests/${widget.id}');
  });
  bool get own => detail?['user_id'] == widget.user['id'];
  bool get canApprove =>
      detail?['status'] == 'PENDING' &&
      !own &&
      records(detail!['steps']).any(
        (s) =>
            s['step'] == detail!['current_step'] &&
            s['approver_id'] == widget.user['id'],
      );
  bool get canCancel {
    if (!own || detail == null) return false;
    if (detail!['status'] == 'PENDING') return true;
    if (detail!['status'] != 'APPROVED' ||
        ['EXPENSE', 'CORRECTION'].contains(detail!['kind'])) {
      return false;
    }
    return canCancelApproved(detail!);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text('申请 #${widget.id}'),
      actions: [
        IconButton(
          tooltip: '刷新',
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh_outlined),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space20),
      children: [
        feedback,
        if (detail != null) ...[
          UrgeButton(
            api: widget.api,
            detail: detail!,
            path: '/requests/${widget.id}',
            onDone: load,
          ),
          if (own &&
              [
                'LEAVE',
                'TRAVEL',
                'OUTING',
                'EXPENSE',
              ].contains(detail!['kind']))
            OutlinedButton(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      final draft = await widget.api.call(
                        '/requests/${widget.id}/copy',
                      ) as Json;
                      if (!mounted || !context.mounted) return;
                      await go(
                        context,
                        RequestFormPage(
                          api: widget.api,
                          initialKind: '${draft['kind']}',
                          initialDraft: draft,
                        ),
                      );
                    }),
              child: const Text('复制再发起'),
            ),
          Text(
            textOf(detail!['kind']),
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          StatusChip(detail!['status']),
          section('申请信息'),
          Text(
            '申请人：${detail!['user_name']}\n提交：${timeOf(detail!['created_at'])}',
          ),
          if (detail!['kind'] == 'CORRECTION')
            Text(
              '补卡时间：${timeOf(detail!['start_at'])}（北京时间）\n类型：${textOf(detail!['punch_kind'])}\n批准后写入补卡记录，不含现场定位。',
            ),
          if (detail!['kind'] == 'OVERTIME')
            Text(
              '兑换比例：${detail!['overtime_ratio']}；批准计入 ${detail!['credited_days']} 天调休（开始年度）',
            ),
          if (!['EXPENSE', 'CORRECTION'].contains(detail!['kind']))
            Text(
              '开始：${timeOf(detail!['start_at'])}\n结束：${timeOf(detail!['end_at'])}\n天数：${detail!['days']} 天（北京时间）',
            ),
          section('申请理由'),
          SelectableText('${detail!['reason']}'),
          if (detail!['attachment_id'] != null)
            OutlinedButton.icon(
              onPressed: busy
                  ? null
                  : () => run(
                      () => openAttachment(
                        widget.api,
                        '${detail!['attachment_id']}',
                        '证明文件',
                      ),
                    ),
              icon: const Icon(Icons.download_outlined),
              label: const Text('下载证明文件'),
            ),
          if (detail!['kind'] == 'EXPENSE') ...[
            section(
              '报销 ${detail!['amount']} 元 · ${textOf(detail!['payment_status'])}',
            ),
            for (final i in records(detail!['items']))
              ListTile(
                title: Text('${i['description']}'),
                trailing: Text('${i['amount']} 元'),
              ),
            section('票据'),
            if (records(detail!['attachments']).isEmpty)
              const Text('历史单据未上传票据'),
            for (final f in records(detail!['attachments']))
              ListTile(
                leading: const Icon(Icons.download_outlined),
                title: Text('${f['original_name']}'),
                onTap: busy
                    ? null
                    : () => run(
                        () => openAttachment(
                          widget.api,
                          '${f['id']}',
                          '${f['original_name']}',
                        ),
                      ),
              ),
            section('人工支付记录'),
            for (final h in records(detail!['payment_history']))
              ListTile(
                title: Text('${textOf(h['status'])} · ${h['actor_name']}'),
                subtitle: Text(
                  '${timeOf(h['acted_at'])}\n${h['reason'] ?? ''}',
                ),
              ),
          ],
          section('抄送同事'),
          Text(
            records(detail!['cc'])
                    .map((u) => '${u['name']}（${u['employee_no']}）')
                    .join('、')
                    .isEmpty
                ? '无'
                : records(detail!['cc'])
                      .map((u) => '${u['name']}（${u['employee_no']}）')
                      .join('、'),
          ),
          section('审批记录'),
          for (final h in records(detail!['reassignments'] ?? const []))
            Text(
              '管理员 ${h['actor_name']} 改派：${h['previous_approver_name']} → ${h['approver_name']} · ${h['note']}',
            ),
          for (final s in records(detail!['steps']))
            Card(
              child: ListTile(
                title: Text('${s['approver_name']} · ${textOf(s['status'])}'),
                subtitle: Text(
                  '${s['actor_name'] != null ? '实际操作：${s['actor_name']} · ' : ''}${s['comment'] ?? '暂无意见'}\n${timeOf(s['acted_at'])}',
                ),
              ),
            ),
          if (canApprove) ...[
            section('处理审批'),
            SegmentedButton<bool>(
              segments: const [
                ButtonSegment(value: true, label: Text('通过')),
                ButtonSegment(value: false, label: Text('驳回')),
              ],
              selected: {approve},
              onSelectionChanged: busy
                  ? null
                  : (v) => setState(() => approve = v.first),
            ),
            field(comment, '审批意见（驳回必填）', lines: 3, max: 1000),
            FilledButton(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      validateDecision(approve, comment.text);
                      await widget.api.call(
                        '/requests/${widget.id}/decision',
                        method: 'POST',
                        data: {
                          'approve': approve,
                          'comment': comment.text.trim(),
                        },
                      );
                      detail = await widget.api.call('/requests/${widget.id}');
                    }, success: '审批已提交'),
              child: const Text('提交审批'),
            ),
          ],
          if (canCancel)
            OutlinedButton(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      if (!await confirm(
                        context,
                        '撤回 / 撤销申请',
                        '待审批可撤回；尚未开始的已批准申请可撤销；加班会退回已兑换调休，须有足够余额。补卡批准后不可撤销。',
                      )) {
                        return;
                      }
                      await widget.api.call(
                        '/requests/${widget.id}/cancel',
                        method: 'POST',
                      );
                      detail = await widget.api.call('/requests/${widget.id}');
                    }, success: '申请已撤回 / 撤销'),
              child: Text(detail!['status'] == 'PENDING' ? '撤回申请' : '撤销申请'),
            ),
        ],
      ],
    ),
  );
}
