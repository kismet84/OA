import 'dart:async';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:oa_employee/pages.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';

class StalledClient extends http.BaseClient {
  bool cancelled = false;
  bool aborted = false;
  bool headers = true;
  @override
  Future<http.StreamedResponse> send(http.BaseRequest request) async {
    (request as http.Abortable).abortTrigger!.then((_) => aborted = true);
    if (!headers) {
      await request.abortTrigger;
      throw http.RequestAbortedException(request.url);
    }
    final stream = StreamController<List<int>>(
      onCancel: () {
        cancelled = true;
      },
    );
    stream.add([123]);
    return http.StreamedResponse(stream.stream, 200);
  }
}

void main() {
  const deadline = Duration(milliseconds: 30);
  Api api(StalledClient client) => Api(
    client: client,
    requestTimeout: deadline,
    uploadTimeout: deadline,
    documentTimeout: deadline,
    downloadTimeout: deadline,
  );
  testWidgets('请求超时后页面 busy 清理并恢复分类操作', (tester) async {
    final client = StalledClient();
    await tester.pumpWidget(
      MaterialApp(
        home: MessagePage(api: api(client), user: const {'id': 1}),
      ),
    );
    await tester.pump();
    expect(
      tester
          .widgetList<ChoiceChip>(find.byType(ChoiceChip))
          .every((c) => c.onSelected == null),
      isTrue,
    );
    await tester.pump(const Duration(milliseconds: 40));
    await tester.pumpAndSettle();
    expect(find.text('请求超时，请检查网络后重试'), findsOneWidget);
    expect(
      tester
          .widgetList<ChoiceChip>(find.byType(ChoiceChip))
          .every((c) => c.onSelected != null),
      isTrue,
    );
    expect(client.cancelled, isTrue);
  });
  test('普通写请求响应体停滞时按总截止时间取消并提示先查询单据', () async {
    final client = StalledClient();
    final watch = Stopwatch()..start();
    await expectLater(
      api(client).call('/requests', method: 'POST'),
      throwsA(
        isA<ApiError>().having((e) => e.message, '提示', contains('先查询单据')),
      ),
    );
    expect(watch.elapsed, lessThan(const Duration(seconds: 1)));
    expect(client.cancelled, isTrue);
    await Future<void>.delayed(Duration.zero);
    expect(client.aborted, isTrue);
  });
  test('上传体停滞取消且清理 attachmentProgress', () async {
    final client = StalledClient();
    await expectLater(
      api(client).upload('test.pdf', Uint8List.fromList([1, 2, 3])),
      throwsA(isA<ApiError>()),
    );
    expect(client.cancelled, isTrue);
    await Future<void>.delayed(Duration.zero);
    expect(client.aborted, isTrue);
    expect(attachmentProgress.value, '');
  });
  test('文档和附件下载总截止时间会取消响应流', () async {
    for (final document in [true, false]) {
      final client = StalledClient();
      final service = api(client);
      await expectLater(
        document
            ? service.documentBytes('/documents/1/file')
            : service.download('1'),
        throwsA(isA<ApiError>()),
      );
      expect(client.cancelled, isTrue);
      await Future<void>.delayed(Duration.zero);
      expect(client.aborted, isTrue);
    }
  });
  test('响应头尚未返回时也中止底层请求', () async {
    final client = StalledClient()..headers = false;
    await expectLater(api(client).call('/test'), throwsA(isA<ApiError>()));
    await Future<void>.delayed(Duration.zero);
    await Future<void>.delayed(Duration.zero);
    expect(client.aborted, isTrue);
  });
  test('快速响应正常且保留自定义请求头', () async {
    final client = MockClient((r) async {
      expect(r.headers['authorization'], 'Bearer fake-session');
      return http.Response('{"id":1}', 200);
    });
    expect(
      await (Api(client: client)..token = 'fake-session').call('/requests'),
      {'id': 1},
    );
  });
}
