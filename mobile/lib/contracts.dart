import 'dart:io';

import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';

import 'api.dart';
import 'urge_button.dart';
import 'ui.dart';

const contractStates = {
  'DRAFT': '草稿',
  'PENDING': '审批中',
  'REJECTED': '已驳回',
  'WITHDRAWN': '已撤回',
  'TO_ARCHIVE': '待归档',
  'ACTIVE': '履行中',
  'EXPIRED': '已到期',
  'TERMINATED': '已终止',
};
const contractTypes = {
  'PURCHASE': '采购',
  'SALES': '销售',
  'SERVICE': '服务',
  'LEASE': '租赁',
  'LABOR': '劳务',
  'OTHER': '其他',
};
const contractFields = {
  'code': '合同编号（留空自动生成）',
  'name': '合同名称',
  'counterparty': '对方单位',
  'contact': '对方联系人',
  'phone': '联系电话',
  'amount': '金额（元，可留空）',
  'signedDate': '签订日期（YYYY-MM-DD）',
  'effectiveDate': '生效日期（YYYY-MM-DD）',
  'expiryDate': '到期日期（YYYY-MM-DD，留空长期）',
  'performance': '付款或履约说明',
  'note': '备注',
};
Future<List<Json>> pickContractFiles(Api api) async {
  final result = await FilePicker.pickFiles(
    type: FileType.custom,
    allowedExtensions: ['pdf', 'png', 'jpg', 'jpeg'],
  );
  final files = <Json>[];
  for (final (i, file) in result.indexed) {
    final size = await file.length();
    if (size == null || size == 0) {
      throw ApiError('每份附件须为1字节至10MB');
    }
    final bytes = await file.readAsBytes();
    files.add({
      'id': await api.upload(
        file.name,
        bytes,
        index: i + 1,
        total: result.length,
      ),
      'name': file.name,
    });
  }
  return files;
}

class ContractsPage extends StatefulWidget {
  final Api api;
  const ContractsPage({super.key, required this.api});
  @override
  State<ContractsPage> createState() => _ContractsState();
}

class _ContractsState extends State<ContractsPage> with Guard<ContractsPage> {
  String scope = 'mine', status = '', type = '';
  int offset = 0;
  final filters = {
    for (final k in ['departmentId', 'counterparty', 'expiryFrom', 'expiryTo'])
      k: TextEditingController(),
  };
  List<Json> rows = [];
  @override
  void initState() {
    super.initState();
    run(fetch);
  }

  @override
  void dispose() {
    for (final c in filters.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> fetch() async {
    final q = {
      'scope': scope,
      'offset': '$offset',
      if (status != '') 'status': status,
      if (type != '') 'type': type,
      for (final e in filters.entries)
        if (e.value.text.isNotEmpty) e.key: e.value.text,
    };
    final data = records(
      await widget.api.call('/contracts?${Uri(queryParameters: q).query}'),
    );
    if (mounted) setState(() => rows = data);
  }

  Future<void> open([int? id]) async {
    await go(
      context,
      id == null
          ? ContractEditPage(api: widget.api)
          : ContractDetailPage(api: widget.api, id: id),
    );
    if (mounted && widget.api.token != null) await run(fetch);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('合同管理')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        feedback,
        FilledButton(
          onPressed: busy ? null : () => open(),
          child: const Text('登记合同'),
        ),
        DropdownButtonFormField<String>(
          initialValue: scope,
          decoration: const InputDecoration(labelText: '合同范围'),
          items: [
            for (final e in {
              'mine': '我的合同',
              'visible': '可见合同',
              'pending': '合同待办',
            }.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
          onChanged: (v) {
            scope = v!;
            offset = 0;
            run(fetch);
          },
        ),
        DropdownButtonFormField<String>(
          initialValue: status,
          decoration: const InputDecoration(labelText: '合同状态'),
          items: [
            const DropdownMenuItem(value: '', child: Text('全部状态')),
            for (final e in contractStates.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
          onChanged: (v) {
            status = v!;
            offset = 0;
            run(fetch);
          },
        ),
        DropdownButtonFormField<String>(
          initialValue: type,
          decoration: const InputDecoration(labelText: '合同类型'),
          items: [
            const DropdownMenuItem(value: '', child: Text('全部类型')),
            for (final e in contractTypes.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
          onChanged: (v) {
            type = v!;
            offset = 0;
            run(fetch);
          },
        ),
        ExpansionTile(
          title: const Text('更多筛选'),
          children: [
            field(filters['departmentId']!, '部门ID'),
            field(filters['counterparty']!, '对方单位关键字'),
            field(filters['expiryFrom']!, '到期起日（YYYY-MM-DD）'),
            field(filters['expiryTo']!, '到期止日（YYYY-MM-DD）'),
            TextButton(
              onPressed: busy
                  ? null
                  : () {
                      offset = 0;
                      run(fetch);
                    },
              child: const Text('筛选合同'),
            ),
          ],
        ),
        if (rows.isEmpty && !busy) empty('暂无合同'),
        for (final r in rows)
          Card(
            child: ListTile(
              title: Text('${r['code']} · ${r['name']}'),
              subtitle: Text(
                '${contractStates[r['status']]} · ${r['counterparty']} · 到期：${r['expiry_date'] ?? '长期'}',
              ),
              onTap: () => open(r['id'] as int),
            ),
          ),
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
            Text('第 ${offset ~/ 200 + 1} 页'),
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

class ContractEditPage extends StatefulWidget {
  final Api api;
  final Json? detail;
  const ContractEditPage({super.key, required this.api, this.detail});
  @override
  State<ContractEditPage> createState() => _ContractEditState();
}

class _ContractEditState extends State<ContractEditPage>
    with Guard<ContractEditPage> {
  late final controls = {
    for (final k in contractFields.keys)
      k: TextEditingController(
        text:
            '${widget.detail?[k.replaceAllMapped(RegExp('[A-Z]'), (m) => '_${m[0]!.toLowerCase()}')] ?? ''}',
      ),
  };
  late String type = widget.detail?['type'] ?? 'SERVICE';
  late List<Json> files = [
    for (final f in widget.detail?['files'] ?? [])
      {'id': f['attachment_id'], 'name': f['original_name']},
  ];
  int? savedId;
  @override
  void dispose() {
    for (final c in controls.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> save(bool submit) async {
    for (final k in ['name', 'counterparty', 'signedDate', 'effectiveDate']) {
      if (controls[k]!.text.trim().isEmpty) {
        throw ApiError('请填写${contractFields[k]}');
      }
    }
    final amount = controls['amount']!.text.trim();
    if (amount.isNotEmpty &&
        (double.tryParse(amount) == null ||
            !double.parse(amount).isFinite ||
            double.parse(amount) < 0)) {
      throw ApiError('金额必须为非负数');
    }
    final data = <String, dynamic>{
      for (final e in controls.entries) e.key: e.value.text.trim(),
      'type': type,
      'amount': amount.isEmpty ? null : double.parse(amount),
      'expiryDate': controls['expiryDate']!.text.isEmpty
          ? null
          : controls['expiryDate']!.text,
      'attachmentIds': files.map((f) => f['id']).toList(),
    };
    final id = savedId ?? widget.detail?['id'];
    savedId =
        (await widget.api.call(
              '/contracts${id == null ? '' : '/$id'}',
              method: id == null ? 'POST' : 'PUT',
              data: data,
            ))['id']
            as int;
    if (submit) {
      await widget.api.call('/contracts/$savedId/submit', method: 'POST');
    }
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('合同登记')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text('经办人为本人，部门随经办人登记。提交后锁定字段，驳回或撤回可修改。'),
        feedback,
        for (final e in controls.entries)
          field(
            e.value,
            contractFields[e.key]!,
            max: ['performance', 'note'].contains(e.key)
                ? 5000
                : e.key == 'phone'
                ? 50
                : e.key == 'code' || e.key == 'contact'
                ? 100
                : 200,
            lines: ['performance', 'note'].contains(e.key) ? 3 : 1,
          ),
        DropdownButtonFormField<String>(
          initialValue: type,
          decoration: const InputDecoration(labelText: '合同类型'),
          items: [
            for (final e in contractTypes.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
          onChanged: (v) => type = v!,
        ),
        TextButton(
          onPressed: busy
              ? null
              : () => run(() async {
                  final picked = await pickContractFiles(widget.api);
                  if (mounted) setState(() => files.addAll(picked));
                }),
          child: const Text('上传合同附件（PDF/PNG/JPEG ≤10MB）'),
        ),
        for (final f in files)
          ListTile(
            title: Text('${f['name']}'),
            trailing: IconButton(
              icon: const Icon(Icons.close),
              onPressed: busy ? null : () => setState(() => files.remove(f)),
            ),
          ),
        FilledButton(
          onPressed: busy ? null : () => run(() => save(false)),
          child: const Text('保存草稿'),
        ),
        FilledButton(
          onPressed: busy ? null : () => run(() => save(true)),
          child: const Text('保存并提交合同'),
        ),
      ],
    ),
  );
}

class ContractDetailPage extends StatefulWidget {
  final Api api;
  final int id;
  const ContractDetailPage({super.key, required this.api, required this.id});
  @override
  State<ContractDetailPage> createState() => _ContractDetailState();
}

class _ContractDetailState extends State<ContractDetailPage>
    with Guard<ContractDetailPage> {
  Json? detail;
  final reason = TextEditingController();
  List<Json> sealed = [];
  @override
  void initState() {
    super.initState();
    run(fetch);
  }

  @override
  void dispose() {
    reason.dispose();
    super.dispose();
  }

  Future<void> fetch() async {
    final d = await widget.api.call('/contracts/${widget.id}') as Json;
    if (mounted) setState(() => detail = d);
  }

  Future<void> act(String action, [Json? data]) async {
    await widget.api.call(
      '/contracts/${widget.id}/$action',
      method: 'POST',
      data: data,
    );
    await fetch();
  }

  Future<void> file(Json f, bool preview) async {
    final bytes = await widget.api.documentBytes(
      '/contracts/${widget.id}/files/${f['attachment_id']}?preview=$preview',
    );
    final dir = await getTemporaryDirectory();
    final name = '${f['original_name']}'.replaceAll(
      RegExp(r'[^\w\u4e00-\u9fff. -]'),
      '_',
    );
    final local = File('${dir.path}/${f['attachment_id']}-$name');
    await local.writeAsBytes(bytes, flush: true);
    final result = await OpenFilex.open(local.path);
    if (result.type != ResultType.done) throw ApiError('附件已读取，设备无法打开该文件');
  }

  @override
  Widget build(BuildContext context) {
    final d = detail;
    final state = d?['workflow_status'];
    return Scaffold(
      appBar: AppBar(title: const Text('合同详情')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          feedback,
          if (d != null) ...[
            UrgeButton(
              api: widget.api,
              detail: d,
              path: '/contracts/${widget.id}',
              onDone: fetch,
            ),
            section('${d['code']} · ${d['name']}'),
            Text(
              '${contractStates[d['status']]} · ${contractTypes[d['type']]}',
            ),
            Text('经办人：${d['user_name']} · 部门：${d['department_name']}'),
            Text(
              '对方单位：${d['counterparty']}\n联系人：${d['contact'] ?? '无'} · 电话：${d['phone'] ?? '无'}\n金额：${d['amount'] ?? '无固定金额'}\n签订：${d['signed_date']} · 生效：${d['effective_date']}\n到期：${d['expiry_date'] ?? '长期'}\n付款或履约说明：${d['performance'] ?? '无'}\n备注：${d['note'] ?? '无'}',
            ),
            if (d['termination_reason'] != null)
              Text('终止原因：${d['termination_reason']}'),
            for (final f in records(d['files']))
              Column(
                children: [
                  Text(
                    '${f['sealed'] == true ? '盖章版' : '草稿/扫描件'} · ${f['original_name']}',
                  ),
                  Wrap(
                    children: [
                      TextButton(
                        onPressed: busy ? null : () => run(() => file(f, true)),
                        child: const Text('预览合同附件'),
                      ),
                      TextButton(
                        onPressed: busy
                            ? null
                            : () => run(() => file(f, false)),
                        child: const Text('下载合同附件'),
                      ),
                    ],
                  ),
                ],
              ),
            section('审批记录'),
            for (final h in records(d['reassignments'] ?? const []))
              Text(
                '管理员 ${h['actor_name']} 改派：${h['previous_approver_name'] ?? '财务或管理员'} → ${h['approver_name']} · ${h['note']}',
              ),
            for (final s in records(d['steps']))
              Text(
                '第 ${s['revision']} 次 · 第 ${s['step']} 级 · ${s['approver_name'] ?? '财务或管理员'} · ${s['status'] == 'APPROVED'
                    ? '通过'
                    : s['status'] == 'REJECTED'
                    ? '驳回'
                    : '待处理'} · ${s['actor_name'] ?? ''} ${s['note'] ?? ''}',
              ),
            if (d['isOwner'] == true &&
                ['DRAFT', 'REJECTED', 'WITHDRAWN'].contains(state)) ...[
              TextButton(
                onPressed: busy
                    ? null
                    : () => run(() async {
                        await go(
                          context,
                          ContractEditPage(api: widget.api, detail: d),
                        );
                        if (mounted) await fetch();
                      }),
                child: const Text('修改合同'),
              ),
              FilledButton(
                onPressed: busy ? null : () => run(() => act('submit')),
                child: const Text('提交合同审批'),
              ),
              if (state == 'DRAFT')
                TextButton(
                  onPressed: busy
                      ? null
                      : () => run(() async {
                          await widget.api.call(
                            '/contracts/${widget.id}',
                            method: 'DELETE',
                          );
                          if (context.mounted) Navigator.pop(context);
                        }),
                  child: const Text('删除合同草稿'),
                ),
            ],
            field(reason, '处理说明（驳回或终止必填）', max: 1000, lines: 3),
            if (d['canApprove'] == true) ...[
              FilledButton(
                onPressed: busy
                    ? null
                    : () => run(
                        () => act('approve', {
                          'approved': true,
                          'note': reason.text,
                        }),
                      ),
                child: const Text('同意合同'),
              ),
              TextButton(
                onPressed: busy
                    ? null
                    : () => run(
                        () => act('approve', {
                          'approved': false,
                          'note': reason.text,
                        }),
                      ),
                child: const Text('驳回合同'),
              ),
            ],
            if (d['isOwner'] == true && state == 'PENDING')
              TextButton(
                onPressed: busy ? null : () => run(() => act('withdraw')),
                child: const Text('撤回合同'),
              ),
            if (d['canArchive'] == true && state == 'TO_ARCHIVE') ...[
              TextButton(
                onPressed: busy
                    ? null
                    : () => run(() async {
                        final picked = await pickContractFiles(widget.api);
                        if (mounted) setState(() => sealed.addAll(picked));
                      }),
                child: const Text('上传盖章版'),
              ),
              for (final f in sealed)
                ListTile(
                  title: Text('${f['name']}'),
                  trailing: IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: busy
                        ? null
                        : () => setState(() => sealed.remove(f)),
                  ),
                ),
              FilledButton(
                onPressed: busy || sealed.isEmpty
                    ? null
                    : () => run(
                        () => act('archive', {
                          'attachmentIds': sealed.map((f) => f['id']).toList(),
                        }),
                      ),
                child: const Text('归档合同'),
              ),
            ],
            if (d['canArchive'] == true &&
                !['DRAFT', 'TERMINATED'].contains(state))
              TextButton(
                onPressed: busy
                    ? null
                    : () =>
                          run(() => act('terminate', {'reason': reason.text})),
                child: const Text('终止合同'),
              ),
          ],
        ],
      ),
    );
  }
}
