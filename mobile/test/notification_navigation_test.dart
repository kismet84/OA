import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/pages.dart';
import 'package:oa_employee/requests.dart';

void main() {
  for (final linked in [true, false]) {
    testWidgets(linked ? '通知按 request_id 进入申请详情' : '历史无关联通知只显示正文', (
      tester,
    ) async {
      final paths = <String>[];
      final api = Api(
        client: MockClient((r) async {
          paths.add(r.url.path);
          Object data = {};
          if (r.url.path == '/api/messages') {
            data = [
              {'id': 7, 'message': '测试审批通知', if (linked) 'request_id': 42},
            ];
          }
          if (r.url.path == '/api/requests/42') {
            data = {
              'id': 42,
              'user_id': 1,
              'kind': 'TRAVEL',
              'status': 'WITHDRAWN',
              'reason': '关联单据内容',
              'steps': [],
              'cc': [],
              'attachments': [],
              'reassignments': [],
            };
          }
          return http.Response(
            jsonEncode(data),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: MessagePage(
            api: api,
            user: const {'id': 1, 'roles': 'EMPLOYEE'},
          ),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('测试审批通知'));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      if (linked) {
        expect(find.byType(RequestDetailPage), findsOneWidget);
        expect(paths, contains('/api/requests/42'));
      } else {
        expect(find.byType(AlertDialog), findsOneWidget);
        expect(find.byType(RequestDetailPage), findsNothing);
        expect(paths.any((p) => p.startsWith('/api/requests/')), isFalse);
        await tester.tap(find.text('关闭'));
        await tester.pumpAndSettle();
      }
      expect(tester.takeException(), isNull);
    });
  }
}
