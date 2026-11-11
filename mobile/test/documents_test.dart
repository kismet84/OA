import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/documents.dart';
import 'package:oa_employee/pages.dart';

http.Response json(Object data, [int status = 200]) => http.Response(
  jsonEncode(data),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
final pixel = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jWTkAAAAASUVORK5CYII=',
);
Json doc(bool download) => {
  'id': 8,
  'title': '员工制度',
  'description': '制度说明',
  'category': 'POLICY',
  'allow_download': download,
  'published_at': '2026-11-12T09:00:00',
  'attachments': [
    {'id': 'file1', 'original_name': '制度.pdf', 'media_type': 'application/pdf'},
  ],
};
void main() {
  testWidgets('文档分类与关键字筛选，打开详情、PDF逐页水印预览；禁止下载隐藏入口', (tester) async {
    tester.view.physicalSize = const Size(1200, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final paths = <String>[];
    Uri? query;
    final api = Api(
      client: MockClient((r) async {
        expect(r.headers['Authorization'], 'Bearer employee');
        paths.add(r.url.path);
        if (r.url.path == '/api/documents') {
          query = r.url;
          return json([doc(false)]);
        }
        if (r.url.path == '/api/documents/8') return json(doc(false));
        if (r.url.path.endsWith('/preview')) {
          return json({'pages': 2, 'watermark': '张三 · E01 · 2026-11-12'});
        }
        if (r.url.path.contains('/pages/')) {
          return http.Response.bytes(
            pixel,
            200,
            headers: {'content-type': 'image/png'},
          );
        }
        throw StateError(r.url.toString());
      }),
    )..token = 'employee';
    await tester.pumpWidget(MaterialApp(home: DocumentsPage(api: api)));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byWidgetPredicate(
        (w) => w is TextField && w.decoration?.labelText == '标题关键字',
      ),
      '员工',
    );
    await tester.tap(find.text('筛选文档'));
    await tester.pumpAndSettle();
    expect(query!.queryParameters['keyword'], '员工');
    await tester.tap(find.text('员工制度'));
    await tester.pumpAndSettle();
    expect(find.text('仅限带水印预览，禁止下载'), findsOneWidget);
    expect(find.text('下载水印件'), findsNothing);
    await tester.tap(find.text('水印预览'));
    await tester.pumpAndSettle();
    expect(find.text('查看水印：张三 · E01 · 2026-11-12'), findsOneWidget);
    expect(find.byType(Image), findsOneWidget);
    expect(find.text('第 1 / 2 页'), findsOneWidget);
    await tester.tap(find.text('下一页预览'));
    await tester.pumpAndSettle();
    expect(find.text('第 2 / 2 页'), findsOneWidget);
    expect(paths, contains('/api/documents/8/attachments/file1/pages/2'));
    expect(
      paths.any(
        (p) => p == '/api/attachments/file1' || p.endsWith('/download'),
      ),
      isFalse,
    );
  });
  testWidgets('允许下载显示入口；下架或移出范围错误可读', (tester) async {
    final api = Api(client: MockClient((r) async => json(doc(true))));
    await tester.pumpWidget(MaterialApp(home: DocumentPage(api: api, id: 8)));
    await tester.pumpAndSettle();
    expect(find.text('下载水印件'), findsOneWidget);
    final forbidden = Api(
      client: MockClient((r) async => json({'message': '文档已下架或您不在接收范围内'}, 403)),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: DocumentPage(
          key: const ValueKey('denied'),
          api: forbidden,
          id: 8,
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('文档已下架或您不在接收范围内'), findsOneWidget);
    expect(find.text('水印预览'), findsNothing);
  });
  testWidgets('系统通知打开对应文档，失去权限显示原因', (tester) async {
    final paths = <String>[];
    final api = Api(
      client: MockClient((r) async {
        paths.add(r.url.path);
        if (r.url.path == '/api/messages') {
          return json([
            {'id': 1, 'document_id': 8, 'message': '文档资料已发布：员工制度'},
          ]);
        }
        if (r.url.path.endsWith('/read')) return json({});
        if (r.url.path == '/api/documents/8') {
          return json({'message': '文档已下架或您不在接收范围内'}, 403);
        }
        throw StateError(r.url.toString());
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: MessagePage(api: api, user: const {'id': 2}),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('文档资料已发布：员工制度'));
    await tester.pumpAndSettle();
    expect(find.text('文档已下架或您不在接收范围内'), findsOneWidget);
    expect(paths, contains('/api/notifications/1/read'));
  });
  test('文档二进制使用受控路径并传播403，绝不退回原件', () async {
    String? requested;
    final api = Api(
      client: MockClient((r) async {
        requested = r.url.path;
        return json({'message': '该文档仅允许预览，禁止下载'}, 403);
      }),
    );
    await expectLater(
      api.documentBytes('/documents/8/attachments/file1/download'),
      throwsA(isA<ApiError>().having((e) => e.status, 'status', 403)),
    );
    expect(requested, '/api/documents/8/attachments/file1/download');
  });
}
