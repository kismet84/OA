import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'theme.dart';

import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';

import 'api.dart';
import 'ui.dart';

const documentCategories = {'POLICY': '公司制度', 'FILE': '通知文件', 'OTHER': '其他'};

class DocumentsPage extends StatefulWidget {
  final Api api;
  const DocumentsPage({super.key, required this.api});
  @override
  State<DocumentsPage> createState() => _DocumentsState();
}

class _DocumentsState extends State<DocumentsPage> with Guard<DocumentsPage> {
  final keyword = TextEditingController(),
      from = TextEditingController(),
      to = TextEditingController();
  String category = '';
  int offset = 0;
  List<Json> rows = [];
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    keyword.dispose();
    from.dispose();
    to.dispose();
    super.dispose();
  }

  Future<void> load() => run(() async {
    final q = {
      'keyword': keyword.text.trim(),
      'category': category,
      'offset': '$offset',
      if (from.text.isNotEmpty) 'publishedFrom': from.text,
      if (to.text.isNotEmpty) 'publishedTo': to.text,
    };
    rows = records(
      await widget.api.call('/documents?${Uri(queryParameters: q).query}'),
    );
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('文档资料')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space16),
      children: [
        feedback,
        Padding(
          padding: const EdgeInsets.symmetric(vertical: OaTokens.space8),
          child: DropdownButtonFormField<String>(
            initialValue: category,
            decoration: const InputDecoration(labelText: '文档分类'),
            items: [
              const DropdownMenuItem(value: '', child: Text('全部分类')),
              for (final e in documentCategories.entries)
                DropdownMenuItem(value: e.key, child: Text(e.value)),
            ],
            onChanged: busy
                ? null
                : (v) {
                    category = v ?? '';
                    offset = 0;
                    load();
                  },
          ),
        ),
        field(keyword, '标题关键字', max: 200),
        field(from, '发布起日（YYYY-MM-DD）'),
        field(to, '发布止日（YYYY-MM-DD）'),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  offset = 0;
                  load();
                },
          child: const Text('筛选文档'),
        ),
        if (rows.isEmpty && !busy) empty('暂无可见文档'),
        for (final r in rows)
          Card(
            child: ListTile(
              title: Text('${r['title']}'),
              subtitle: Text(
                '${documentCategories[r['category']]} · ${timeOf(r['published_at'])}',
              ),
              trailing: const Icon(Icons.chevron_right_outlined),
              onTap: () => go(
                context,
                DocumentPage(api: widget.api, id: r['id'] as int),
              ),
            ),
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
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
            Text('第 ${offset ~/ 200 + 1} 页'),
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

class DocumentPage extends StatefulWidget {
  final Api api;
  final int id;
  const DocumentPage({super.key, required this.api, required this.id});
  @override
  State<DocumentPage> createState() => _DocumentState();
}

class _DocumentState extends State<DocumentPage> with Guard<DocumentPage> {
  Json? data, selected;
  Uint8List? bytes;
  int page = 1, pages = 1;
  String watermark = '';
  @override
  void initState() {
    super.initState();
    run(() async {
      data = await widget.api.call('/documents/${widget.id}') as Json;
    });
  }

  String base(Json file) => '/documents/${widget.id}/attachments/${file['id']}';
  Future<void> preview(Json file) => run(() async {
    bytes = null;
    selected = null;
    final info = await widget.api.call('${base(file)}/preview') as Json;
    final image = await widget.api.documentBytes('${base(file)}/pages/1');
    selected = file;
    pages = info['pages'] as int;
    watermark = info['watermark'] as String;
    page = 1;
    bytes = image;
  });
  Future<void> turn(int next) => run(() async {
    bytes = null;
    final image = await widget.api.documentBytes(
      '${base(selected!)}/pages/$next',
    );
    page = next;
    bytes = image;
  });
  Future<void> download(Json file) => run(() async {
    final output = await widget.api.documentBytes('${base(file)}/download');
    final dir = await getApplicationDocumentsDirectory();
    final safe = '${file['original_name']}'.replaceAll(
      RegExp(r'[^\w\u4e00-\u9fff. -]'),
      '_',
    );
    final ext = file['media_type'] == 'application/pdf' ? 'pdf' : 'png';
    final path = File('${dir.path}/${file['id']}-$safe-水印.$ext');
    await path.writeAsBytes(output, flush: true);
    final result = await OpenFilex.open(path.path);
    if (result.type != ResultType.done) {
      throw ApiError('水印文件已保存，设备未能打开：${result.message}');
    }
  }, success: '水印文件已保存');
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('文档详情')),
    body: ListView(
      padding: const EdgeInsets.all(OaTokens.space16),
      children: [
        feedback,
        if (data != null) ...[
          section('${data!['title']}'),
          Text(
            '${documentCategories[data!['category']]} · ${timeOf(data!['published_at'])}',
          ),
          if (data!['description'] != null) Text('${data!['description']}'),
          if (data!['allow_download'] != true) const Text('仅限带水印预览，禁止下载'),
          for (final f in records(data!['attachments']))
            Card(
              child: Padding(
                padding: const EdgeInsets.all(OaTokens.space12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${f['original_name']}'),
                    Wrap(
                      spacing: 12,
                      children: [
                        OutlinedButton(
                          onPressed: busy ? null : () => preview(f),
                          child: const Text('水印预览'),
                        ),
                        if (data!['allow_download'] == true)
                          TextButton(
                            onPressed: busy ? null : () => download(f),
                            child: const Text('下载水印件'),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
        ],
        if (selected != null) ...[
          Text('查看水印：$watermark'),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              TextButton(
                onPressed: busy || page <= 1 ? null : () => turn(page - 1),
                child: const Text('上一页预览'),
              ),
              Text('第 $page / $pages 页'),
              TextButton(
                onPressed: busy || page >= pages ? null : () => turn(page + 1),
                child: const Text('下一页预览'),
              ),
            ],
          ),
          if (bytes != null)
            InteractiveViewer(
              minScale: 1,
              maxScale: 5,
              child: Image.memory(
                bytes!,
                gaplessPlayback: false,
                semanticLabel: '带查看人水印的文档预览',
              ),
            ),
          const Text('首次成功打开预览会记录已读回执。'),
        ],
      ],
    ),
  );
}
