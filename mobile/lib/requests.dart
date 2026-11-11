import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import 'api.dart';
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
  const RequestFormPage({super.key, required this.api});
  @override
  State<RequestFormPage> createState() => _RequestFormState();
}

class _RequestFormState extends State<RequestFormPage>
    with Guard<RequestFormPage> {
  String kind = 'LEAVE';
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
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() => run(() async {
    types = records(await widget.api.call('/leave-types'));
    colleagues = records(await widget.api.call('/colleagues'));
    typeId = types.isEmpty ? null : types.first['id'];
    ready = true;
  });
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
        } else {
          end = d;
        }
      });
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
    for (final f in result) {
      final size = await f.length();
      if (size == null || size > 10 * 1024 * 1024 || size == 0) {
        throw ApiError('每份附件须为1字节至10MB');
      }
      final bytes = await f.readAsBytes();
      final id = await widget.api.upload(f.name, bytes);
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
    final type = types.where((t) => t['id'] == typeId).firstOrNull;
    final payload = requestPayload(
      kind: kind,
      reason: reason.text,
      typeId: typeId,
      startAt: '${date(start)}T$startTime:00',
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
        padding: const EdgeInsets.all(20),
        children: [
          feedback,
          if (!ready && !busy)
            TextButton(onPressed: load, child: const Text('重新加载假期与同事')),
          DropdownButtonFormField<String>(
            initialValue: kind,
            decoration: const InputDecoration(labelText: '申请类型'),
            items: [
              for (final k in ['LEAVE', 'TRAVEL', 'OUTING', 'EXPENSE'])
                DropdownMenuItem(value: k, child: Text(textOf(k))),
            ],
            onChanged: (v) => setState(() {
              kind = v!;
              files.clear();
            }),
          ),
          if (kind == 'LEAVE') ...[
            DropdownButtonFormField<int>(
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
            if (types.isEmpty && ready) const Text('暂无启用的假期类型，请联系管理员'),
          ],
          if (kind != 'EXPENSE') ...[
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
            const Text('上午、下午各半天，按自然日含周末。跨年请拆单；天数由服务端计算。'),
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
                  padding: const EdgeInsets.all(12),
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
            trailing: const Icon(Icons.chevron_right),
            onTap: pickCc,
          ),
          section(kind == 'EXPENSE' ? '票据（必填，可多张）' : '证明文件'),
          const Text('支持 PDF / PNG / JPEG，每份最多10MB。'),
          for (final f in files)
            ListTile(
              title: Text('${f['name']}'),
              trailing: IconButton(
                tooltip: '移除附件',
                icon: const Icon(Icons.close),
                onPressed: () => setState(() => files.remove(f)),
              ),
            ),
          OutlinedButton.icon(
            onPressed: pickFiles,
            icon: const Icon(Icons.upload_file),
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
    if (detail!['status'] != 'APPROVED' || detail!['kind'] == 'EXPENSE') {
      return false;
    }
    final start = DateTime.tryParse('${detail!['start_at']}');
    return start != null &&
        start.isAfter(
          DateTime.parse(shanghaiNow().toIso8601String().replaceAll('Z', '')),
        );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text('申请 #${widget.id}'),
      actions: [
        IconButton(
          tooltip: '刷新',
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        feedback,
        if (detail != null) ...[
          Text(
            '${textOf(detail!['kind'])} · ${textOf(detail!['status'])}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          section('申请信息'),
          Text(
            '申请人：${detail!['user_name']}\n提交：${timeOf(detail!['created_at'])}',
          ),
          if (detail!['kind'] != 'EXPENSE')
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
              icon: const Icon(Icons.download),
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
                leading: const Icon(Icons.download),
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
          for (final s in records(detail!['steps']))
            Card(
              child: ListTile(
                title: Text('${s['approver_name']} · ${textOf(s['status'])}'),
                subtitle: Text(
                  '${s['comment'] ?? '暂无意见'}\n${timeOf(s['acted_at'])}',
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
                        '待审批可撤回；尚未开始的已批准申请可撤销并按规则返还余额。',
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
