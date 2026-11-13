import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/urge_button.dart';
import 'package:oa_employee/requests.dart';
import 'package:oa_employee/attendance.dart';

Api mockApi(dynamic Function(http.Request) handle) => Api(
  client: MockClient(
    (r) async => http.Response(
      jsonEncode(handle(r)),
      200,
      headers: {'content-type': 'application/json; charset=utf-8'},
    ),
  ),
)..token = 'test';
void main() {
  testWidgets('催办按钮发送请求，冷却中禁用并提示时间', (tester) async {
    var sent = 0, done = 0;
    final api = mockApi((r) {
      expect(r.url.path, '/api/requests/1/urge');
      expect(r.method, 'POST');
      sent++;
      return {'message': '已催办 李主管'};
    });
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: UrgeButton(
            api: api,
            detail: {'canUrge': true},
            path: '/requests/1',
            onDone: () async {
              done++;
            },
          ),
        ),
      ),
    );
    await tester.tap(find.text('催办'));
    await tester.pumpAndSettle();
    expect(sent, 1);
    expect(done, 1);
    expect(find.text('已催办 李主管'), findsOneWidget);
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: UrgeButton(
            key: const ValueKey('cool'),
            api: api,
            detail: {
              'canUrge': true,
              'urgeAvailableAt': DateTime.now()
                  .toUtc()
                  .add(const Duration(hours: 2))
                  .toIso8601String(),
            },
            path: '/requests/1',
            onDone: () async {},
          ),
        ),
      ),
    );
    expect(
      tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed,
      isNull,
    );
    expect(find.textContaining('可再次催办时间'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets('从模板填单清空附件日期并保留理由', (tester) async {
    final api = mockApi((r) {
      switch (r.url.path) {
        case '/api/attendance-policy':
          return {'correction_days': 3};
        case '/api/leave-types':
        case '/api/colleagues':
          return [];
        case '/api/request-templates':
          return [
            {
              'id': 1,
              'name': '拜访客户',
              'kind': 'OUTING',
              'payload': {'reason': '客户沟通', 'ccUserIds': []},
            },
          ];
        default:
          throw StateError('${r.url}');
      }
    });
    await tester.pumpWidget(MaterialApp(home: RequestFormPage(api: api)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('从模板填单'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('拜访客户（外出）'));
    await tester.pumpAndSettle();
    expect(find.text('请重新选择日期，附件不会复制'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.widgetWithText(TextField, '申请理由'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(
      tester
          .widget<TextField>(find.widgetWithText(TextField, '申请理由'))
          .controller!
          .text,
      '客户沟通',
    );
  });
  testWidgets('我的考勤高亮异常，补卡入口预填日期类型', (tester) async {
    final api = mockApi((r) {
      if (r.url.path == '/api/attendance/summary') {
        return [
          {
            'user_id': 1,
            'user_name': '员工',
            'expectedDays': 1,
            'normalDays': 0,
            'lateCount': 0,
            'earlyCount': 0,
            'missingCount': 1,
            'leaveDays': 0,
            'fieldDays': 0,
          },
        ];
      }
      if (r.url.path == '/api/attendance/daily') {
        return [
          {
            'day': '2026-11-12',
            'status': 'MISSING',
            'firstPunch': null,
            'lastPunch': null,
            'lateMinutes': 0,
            'earlyMinutes': 0,
            'missingPunch': '上班卡',
            'requestIds': [],
            'field': false,
          },
        ];
      }
      if (r.url.path == '/api/attendance-policy') return {'correction_days': 3};
      return [];
    });
    await tester.pumpWidget(
      MaterialApp(
        home: AttendancePage(api: api, user: {'id': 1}),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('2026-11-12 · 缺卡'), findsOneWidget);
    await tester.tap(find.text('发起补卡'));
    await tester.pumpAndSettle();
    expect(find.text('补卡类型'), findsOneWidget);
    expect(find.textContaining('2026-11-12'), findsOneWidget);
  });
}
