import 'package:flutter/material.dart';

import 'theme.dart';

import 'api.dart';
import 'urge_button.dart';
import 'ui.dart';

const assetStates = {
  'PENDING': OaTokens.statusPendingLabel,
  'READY': OaTokens.statusReadyLabel,
  'ISSUED': OaTokens.statusIssuedLabel,
  'IN_USE': OaTokens.statusInUseLabel,
  'RETURNED': OaTokens.statusReturnedLabel,
  'REJECTED': OaTokens.statusRejectedLabel,
  'WITHDRAWN': OaTokens.statusWithdrawnLabel,
};

class AssetsPage extends StatefulWidget {
  final Api api;
  const AssetsPage({super.key, required this.api});
  @override
  State<AssetsPage> createState() => _AssetsState();
}

class _AssetsState extends State<AssetsPage> with Guard<AssetsPage> {
  String scope = 'mine';
  int offset = 0;
  List<Json> rows = [];
  @override
  void initState() {
    super.initState();
    run(fetch);
  }

  Future<void> fetch() async {
    final data = records(
      await widget.api.call(
        scope == 'holdings'
            ? '/assets/mine?offset=$offset'
            : '/assets/requests?scope=$scope&offset=$offset',
      ),
    );
    if (mounted) setState(() => rows = data);
  }

  Future<void> open([int? id]) async {
    await go(
      context,
      id == null
          ? AssetApplyPage(api: widget.api)
          : AssetDetailPage(api: widget.api, id: id),
    );
    if (mounted && widget.api.token != null) await run(fetch);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('资产领用')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space16),
      children: [
        const Text('简单领用流程 · 北京时间 · 每页最多200条'),
        feedback,
        FilledButton(
          onPressed: busy ? null : () => open(),
          child: const Text('发起领用申请'),
        ),
        Wrap(
          children: [
            for (final e in {
              'mine': '我的申请',
              'holdings': '我的资产',
              'pending': '资产待办',
            }.entries)
              TextButton(
                onPressed: busy
                    ? null
                    : () {
                        setState(() {
                          scope = e.key;
                          offset = 0;
                        });
                        run(fetch);
                      },
                child: Text(e.value),
              ),
          ],
        ),
        OutlinedButton(
          onPressed: busy ? null : () => run(fetch),
          child: const Text('刷新资产'),
        ),
        for (final r in rows)
          Card(
            child: ListTile(
              title: Text(
                scope == 'holdings'
                    ? '${r['code']} · ${r['name']}'
                    : '${r['category_name']} · ${r['quantity']}',
              ),
              leading: r['status'] == null
                  ? null
                  : StatusChip(r['status'], label: assetStates[r['status']]),
              subtitle: Text(
                scope == 'holdings'
                    ? '${r['returned_at'] == null ? '当前在我名下' : '历史领用'} · ${timeOf(r['issued_at'])}'
                    : '${r['user_name']} · ${r['purpose']}${r['return_requested'] == true ? ' · 待确认归还' : ''}',
              ),
              onTap: () => open((r['request_id'] ?? r['id']) as int),
            ),
          ),
        if (rows.isEmpty && !busy) const Text('暂无资产领用记录'),
        Text('第 ${offset ~/ 200 + 1} 页'),
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

class AssetApplyPage extends StatefulWidget {
  final Api api;
  const AssetApplyPage({super.key, required this.api});
  @override
  State<AssetApplyPage> createState() => _AssetApplyState();
}

class _AssetApplyState extends State<AssetApplyPage>
    with Guard<AssetApplyPage> {
  List<Json> catalog = [];
  String? choice;
  bool needsReturn = true;
  final quantity = TextEditingController(text: '1'),
      purpose = TextEditingController(),
      date = TextEditingController(),
      returnDate = TextEditingController();
  @override
  void initState() {
    super.initState();
    run(() async {
      final c = records(await widget.api.call('/assets/catalog'));
      if (mounted) setState(() => catalog = c);
    });
  }

  @override
  void dispose() {
    for (final c in [quantity, purpose, date, returnDate]) {
      c.dispose();
    }
    super.dispose();
  }

  Json? get selected {
    for (final c in catalog) {
      if ('${c['kind']}:${c['id']}' == choice) return c;
    }
    return null;
  }

  Future<void> submit() => run(() async {
    final c = selected;
    final n = int.tryParse(quantity.text);
    if (c == null) throw ApiError('请选择资产');
    if (n == null || n < 1 || n > 100000) throw ApiError('数量须为1至100000的整数');
    if (purpose.text.trim().isEmpty) throw ApiError('请填写用途');
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(date.text)) {
      throw ApiError('请填写期望领用日期（YYYY-MM-DD）');
    }
    await widget.api.call(
      '/assets/requests',
      method: 'POST',
      data: {
        'categoryId': c['kind'] == 'ITEM' ? c['id'] : null,
        'consumableId': c['kind'] == 'CONSUMABLE' ? c['id'] : null,
        'quantity': n,
        'purpose': purpose.text,
        'expectedDate': date.text,
        'needsReturn': c['kind'] == 'ITEM' && needsReturn,
        'returnDate':
            c['kind'] == 'ITEM' && needsReturn && returnDate.text.isNotEmpty
            ? returnDate.text
            : null,
      },
    );
    if (mounted) Navigator.pop(context);
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('发起领用申请')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space16),
      children: [
        feedback,
        Padding(
          padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
          child: DropdownButtonFormField<String>(
            initialValue: choice,
            decoration: const InputDecoration(labelText: '可申请资产'),
            isExpanded: true,
            items: [
              for (final c in catalog)
                DropdownMenuItem(
                  value: '${c['kind']}:${c['id']}',
                  child: Text(
                    '${c['name']} · 可用 ${c['available']} ${c['unit'] ?? '件'}',
                  ),
                ),
            ],
            onChanged: busy
                ? null
                : (v) => setState(() {
                    choice = v;
                    needsReturn = selected?['kind'] == 'ITEM';
                  }),
          ),
        ),
        field(quantity, '数量', keyboard: TextInputType.number),
        field(purpose, '用途', lines: 3, max: 2000),
        field(date, '期望领用日期（YYYY-MM-DD）'),
        if (selected?['kind'] == 'ITEM') ...[
          CheckboxListTile(
            title: const Text('需要归还'),
            value: needsReturn,
            onChanged: busy ? null : (v) => setState(() => needsReturn = v!),
          ),
          if (needsReturn) field(returnDate, '预计归还日期（YYYY-MM-DD，可选）'),
        ],
        const Text('直属上级审批一次后由行政发放；无直属上级直接待发放。消耗品无需归还。'),
        FilledButton(
          onPressed: busy ? null : submit,
          child: const Text('提交领用申请'),
        ),
      ],
    ),
  );
}

class AssetDetailPage extends StatefulWidget {
  final Api api;
  final int id;
  const AssetDetailPage({super.key, required this.api, required this.id});
  @override
  State<AssetDetailPage> createState() => _AssetDetailState();
}

class _AssetDetailState extends State<AssetDetailPage>
    with Guard<AssetDetailPage> {
  Json? detail;
  final note = TextEditingController();
  @override
  void initState() {
    super.initState();
    run(fetch);
  }

  @override
  void dispose() {
    note.dispose();
    super.dispose();
  }

  Future<void> fetch() async {
    final d = await widget.api.call('/assets/requests/${widget.id}') as Json;
    if (mounted) setState(() => detail = d);
  }

  Future<void> act(String action, [Json? data]) => run(() async {
    await widget.api.call(
      '/assets/requests/${widget.id}/$action',
      method: 'POST',
      data: data,
    );
    if (action == 'approve') {
      if (mounted) Navigator.pop(context);
    } else {
      await fetch();
    }
  });
  @override
  Widget build(BuildContext context) {
    final d = detail;
    return Scaffold(
      appBar: AppBar(title: const Text('资产领用详情')),
      body: ListView(
        padding: const EdgeInsets.all(OaTokens.space16),
        children: [
          feedback,
          if (d != null) ...[
            UrgeButton(
              api: widget.api,
              detail: d,
              path: '/assets/requests/${widget.id}',
              onDone: fetch,
            ),
            Text('${d['category_name']} · ${d['user_name']}'),
            Text('数量：${d['quantity']}'),
            StatusChip(d['status'], label: assetStates[d['status']]),
            Text('用途：${d['purpose']}'),
            Text('期望领用：${d['expected_date']}'),
            Text(
              '${d['needs_return'] == true ? '需要归还' : '无需归还'} · 预计归还：${d['return_date'] ?? '未填写'}',
            ),
            Text(
              '发放：${timeOf(d['issued_at'])} · 归还：${timeOf(d['returned_at'])}',
            ),
            for (final h in records(d['reassignments'] ?? const []))
              Text(
                '管理员 ${h['actor_name']} 改派：${h['previous_approver_name']} → ${h['approver_name']} · ${h['note']}',
              ),
            Text('实际审批人：${d['approval_actor_name'] ?? '未处理/历史记录'}'),
            Text('审批说明：${d['approval_note'] ?? '无'}'),
            Text('发放说明：${d['issue_note'] ?? '无'}'),
            Text('归还说明：${d['return_note'] ?? '无'}'),
            for (final a in records(d['assignments']))
              ListTile(
                title: Text('${a['code']} · ${a['name']}'),
                subtitle: Text(a['returned_at'] == null ? '当前领用' : '已归还'),
              ),
            if (d['return_requested'] == true) const Text('已登记归还，等待行政确认'),
            field(note, '处理说明（驳回必填）', lines: 3, max: 1000),
            if (d['canApprove'] == true) ...[
              FilledButton(
                onPressed: busy
                    ? null
                    : () =>
                          act('approve', {'approved': true, 'note': note.text}),
                child: const Text('同意领用'),
              ),
              OutlinedButton(
                onPressed: busy
                    ? null
                    : () => act('approve', {
                        'approved': false,
                        'note': note.text,
                      }),
                child: const Text('驳回领用'),
              ),
            ],
            if (d['isOwner'] == true &&
                ['PENDING', 'READY'].contains(d['status']))
              OutlinedButton(
                onPressed: busy ? null : () => act('withdraw'),
                child: const Text('撤回领用'),
              ),
            if (d['isOwner'] == true &&
                d['kind'] == 'ITEM' &&
                ['IN_USE', 'ISSUED'].contains(d['status']) &&
                d['return_requested'] != true)
              FilledButton(
                onPressed: busy
                    ? null
                    : () => act('return', {'note': note.text}),
                child: const Text('发起归还登记'),
              ),
          ],
        ],
      ),
    );
  }
}
