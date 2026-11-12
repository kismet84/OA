import 'package:flutter/material.dart';

import 'api.dart';
import 'ui.dart';

const visitorStates = {
  'WAITING': '待到访',
  'ARRIVED': '已到访',
  'LEFT': '已离开',
  'CANCELLED': '已取消',
  'EXPIRED': '已过期',
};
const visitorFields = {
  'name': '访客姓名',
  'phone': '手机号',
  'company': '单位',
  'purpose': '来访事由',
  'people': '人数',
  'companions': '随行人姓名（可空）',
  'startAt': '预计开始时间（北京时间，YYYY-MM-DD HH:mm）',
  'endAt': '预计结束时间（北京时间，YYYY-MM-DD HH:mm）',
  'plate': '车牌号（可空）',
  'note': '备注（可空）',
};
const visitorLimits = {
  'name': 100,
  'phone': 20,
  'company': 200,
  'purpose': 1000,
  'people': 3,
  'companions': 1000,
  'startAt': 16,
  'endAt': 16,
  'plate': 30,
  'note': 2000,
};

// Interpret user input as Shanghai wall time; never convert via the phone timezone.
Json visitorInput(Map<String, String> values) {
  for (final key in [
    'name',
    'phone',
    'company',
    'purpose',
    'people',
    'startAt',
    'endAt',
  ]) {
    if ((values[key] ?? '').trim().isEmpty) {
      throw ApiError('请填写${visitorFields[key]}');
    }
  }
  if (!RegExp(r'^[0-9]{7,20}$').hasMatch(values['phone']!.trim())) {
    throw ApiError('手机号须为7至20位数字');
  }
  final people = int.tryParse(values['people']!);
  if (people == null || people < 1 || people > 100) throw ApiError('人数须为1至100');
  DateTime parse(String key) {
    final text = values[key]!.trim().replaceAll(' ', 'T');
    final date = DateTime.tryParse('${text}Z');
    if (!RegExp(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$').hasMatch(text) ||
        date == null ||
        !date.toIso8601String().startsWith('$text:00')) {
      throw ApiError('请按YYYY-MM-DD HH:mm填写有效的北京时间');
    }
    return date;
  }

  final start = parse('startAt'), end = parse('endAt');
  if (!end.isAfter(start)) throw ApiError('预计结束时间必须晚于开始时间');
  return {
    for (final e in values.entries) e.key: e.value.trim(),
    'people': people,
    'startAt': values['startAt']!.trim().replaceAll(' ', 'T'),
    'endAt': values['endAt']!.trim().replaceAll(' ', 'T'),
  };
}

class VisitorsPage extends StatefulWidget {
  final Api api;
  const VisitorsPage({super.key, required this.api});
  @override
  State<VisitorsPage> createState() => _VisitorsState();
}

class _VisitorsState extends State<VisitorsPage> with Guard<VisitorsPage> {
  List<Json> rows = [];
  String status = '';
  int offset = 0;
  final filters = {
    for (final k in ['from', 'to', 'keyword']) k: TextEditingController(),
  };
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
      'mine': 'true',
      'offset': '$offset',
      if (status.isNotEmpty) 'status': status,
      for (final e in filters.entries)
        if (e.value.text.isNotEmpty) e.key: e.value.text.trim(),
    };
    final data = records(
      await widget.api.call('/visitors?${Uri(queryParameters: q).query}'),
    );
    if (mounted) setState(() => rows = data);
  }

  Future<void> open([int? id]) async {
    await go(
      context,
      id == null
          ? VisitorEditPage(api: widget.api)
          : VisitorDetailPage(api: widget.api, id: id),
    );
    if (mounted && widget.api.token != null) await run(fetch);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('我的访客'),
      actions: [
        IconButton(
          onPressed: busy ? null : () => run(fetch),
          icon: const Icon(Icons.refresh),
          tooltip: '刷新访客',
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        feedback,
        FilledButton(
          onPressed: busy ? null : () => open(),
          child: const Text('预约访客'),
        ),
        const Text('无需审批。时间按北京时间填写，签到后将在消息中心通知您。'),
        DropdownButtonFormField<String>(
          initialValue: status,
          decoration: const InputDecoration(labelText: '访客状态'),
          items: [
            const DropdownMenuItem(value: '', child: Text('全部状态')),
            for (final e in visitorStates.entries)
              DropdownMenuItem(value: e.key, child: Text(e.value)),
          ],
          onChanged: (v) {
            status = v!;
            offset = 0;
            run(fetch);
          },
        ),
        ExpansionTile(
          title: const Text('更多筛选'),
          children: [
            field(filters['from']!, '开始日期（YYYY-MM-DD）'),
            field(filters['to']!, '结束日期（YYYY-MM-DD）'),
            field(filters['keyword']!, '访客关键字', max: 100),
            TextButton(
              onPressed: busy
                  ? null
                  : () {
                      offset = 0;
                      run(fetch);
                    },
              child: const Text('查询访客'),
            ),
          ],
        ),
        if (rows.isEmpty && !busy) empty('暂无访客预约'),
        for (final r in rows)
          Card(
            child: ListTile(
              title: Text('${r['name']} · ${visitorStates[r['status']]}'),
              subtitle: Text(
                '访客码：${r['code']}\n${timeOf(r['start_at'])} 至 ${timeOf(r['end_at'])}\n${r['company']} · ${r['phone']}',
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

class VisitorEditPage extends StatefulWidget {
  final Api api;
  const VisitorEditPage({super.key, required this.api});
  @override
  State<VisitorEditPage> createState() => _VisitorEditState();
}

class _VisitorEditState extends State<VisitorEditPage>
    with Guard<VisitorEditPage> {
  final controls = {
    for (final k in visitorFields.keys)
      k: TextEditingController(text: k == 'people' ? '1' : ''),
  };
  @override
  void dispose() {
    for (final c in controls.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    final data = visitorInput({
      for (final e in controls.entries) e.key: e.value.text,
    });
    final result = await widget.api.call(
      '/visitors',
      method: 'POST',
      data: data,
    );
    if (!mounted) return;
    await Navigator.of(context).pushReplacement(
      MaterialPageRoute<void>(
        builder: (_) =>
            VisitorDetailPage(api: widget.api, id: result['id'] as int),
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('预约访客')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text('本人作为接待人。姓名、手机号等仅用于来访登记。无需审批，凭六位访客码到前台签到。'),
        feedback,
        for (final e in visitorFields.entries)
          field(
            controls[e.key]!,
            e.value,
            max: visitorLimits[e.key],
            keyboard: ['phone', 'people'].contains(e.key)
                ? TextInputType.number
                : null,
            lines: ['purpose', 'note'].contains(e.key) ? 3 : 1,
          ),
        FilledButton(
          onPressed: busy ? null : () => run(save),
          child: const Text('确认预约'),
        ),
      ],
    ),
  );
}

class VisitorDetailPage extends StatefulWidget {
  final Api api;
  final int id;
  const VisitorDetailPage({super.key, required this.api, required this.id});
  @override
  State<VisitorDetailPage> createState() => _VisitorDetailState();
}

class _VisitorDetailState extends State<VisitorDetailPage>
    with Guard<VisitorDetailPage> {
  Json? detail;
  @override
  void initState() {
    super.initState();
    run(fetch);
  }

  Future<void> fetch() async {
    final data = await widget.api.call('/visitors/${widget.id}') as Json;
    if (mounted) setState(() => detail = data);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('访客详情'),
      actions: [
        IconButton(
          onPressed: busy ? null : () => run(fetch),
          icon: const Icon(Icons.refresh),
          tooltip: '刷新状态',
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        feedback,
        if (detail != null) ...[
          section('${detail!['name']} · ${visitorStates[detail!['status']]}'),
          SelectableText(
            '访客码：${detail!['code']}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          for (final e in {
            'host_name': '接待人',
            'phone': '手机号',
            'company': '单位',
            'purpose': '来访事由',
            'people': '人数',
            'companions': '随行人姓名',
            'start_at': '预计开始',
            'end_at': '预计结束',
            'checked_in_at': '签到时间',
            'checked_out_at': '签离时间',
            'plate': '车牌号',
            'note': '备注',
          }.entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: Text(
                '${e.value}：${e.key.endsWith('_at') ? timeOf(detail![e.key]) : detail![e.key] ?? '未填写'}',
              ),
            ),
          const Text('以上时间均为北京时间。'),
          if (detail!['isOwner'] == true && detail!['status'] == 'WAITING')
            OutlinedButton(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      if (!await confirm(context, '取消预约', '确认取消这条待到访预约？')) {
                        return;
                      }
                      await widget.api.call(
                        '/visitors/${widget.id}/cancel',
                        method: 'POST',
                      );
                      await fetch();
                    }),
              child: const Text('取消预约'),
            ),
        ],
      ],
    ),
  );
}
