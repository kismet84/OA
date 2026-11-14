import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/requests.dart';
import 'package:oa_employee/pages.dart';
import 'package:oa_employee/validation.dart';

void main() {
  test('补卡无定位和网络、过去任意分钟、必填原因及类型、拒绝未来', () {
    final past = shanghaiNow().subtract(const Duration(days: 1));
    final at =
        '${past.year}-${past.month.toString().padLeft(2, '0')}-${past.day.toString().padLeft(2, '0')}T10:21:00';
    final payload = requestPayload(
      kind: 'CORRECTION',
      reason: '忘记打卡',
      startAt: at,
      punchKind: 'NORMAL',
      cc: [2, 2],
    );
    expect(payload, {
      'kind': 'CORRECTION',
      'reason': '忘记打卡',
      'startAt': at,
      'punchKind': 'NORMAL',
      'ccUserIds': [2],
    });
    expect(
      () => requestPayload(
        kind: 'CORRECTION',
        reason: '',
        startAt: at,
        punchKind: 'NORMAL',
      ),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => requestPayload(kind: 'CORRECTION', reason: '原因', startAt: at),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => requestPayload(
        kind: 'CORRECTION',
        reason: '原因',
        startAt: shanghaiNow().add(const Duration(days: 1)).toIso8601String(),
        punchKind: 'FIELD',
      ),
      throwsA(isA<ApiError>()),
    );
  });

  test('加班接受过去及跨年半天，结束时间必填且须晚于开始', () {
    final payload = requestPayload(
      kind: 'OVERTIME',
      reason: '项目支持',
      startAt: '2024-12-31T13:00:00',
      endAt: '2025-01-01T13:00:00',
    );
    expect(payload['kind'], 'OVERTIME');
    expect(
      () => requestPayload(
        kind: 'OVERTIME',
        reason: '支持',
        startAt: '2024-12-31T13:00:00',
      ),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => requestPayload(
        kind: 'OVERTIME',
        reason: '支持',
        startAt: '2024-12-31T13:00:00',
        endAt: '2024-12-31T13:00:00',
      ),
      throwsA(isA<ApiError>()),
    );
  });

  test('撤销以带时区时间戳比较，补卡批准后永不可撤销', () {
    final now = DateTime.parse('2026-11-11T01:00:00Z');
    final request = <String, dynamic>{
      'kind': 'OVERTIME',
      'status': 'APPROVED',
      'start_at': '2026-11-11T13:00:00.000+08:00',
    };
    expect(canCancelApproved(request, now: now), true);
    expect(
      canCancelApproved(request, now: DateTime.parse('2026-11-11T05:00:00Z')),
      false,
    );
    expect(
      canCancelApproved({...request, 'kind': 'CORRECTION'}, now: now),
      false,
    );
  });

  testWidgets('补卡表单使用后台期限，提交无定位，并能选择抄送', (tester) async {
    Json? submitted;
    final api = Api(
      client: MockClient((r) async {
        dynamic result;
        switch (r.url.path) {
          case '/api/attendance-policy':
            result = {
              'correction_days': 5,
              'overtime_ratio': 1.25,
              'leave_threshold': 2,
            };
          case '/api/request-templates':
          case '/api/leave-types':
            result = [];
          case '/api/colleagues':
            result = [
              {
                'id': 2,
                'name': '李同事',
                'employee_no': 'E002',
                'department_name': '研发',
              },
            ];
          case '/api/requests':
            submitted = jsonDecode(r.body);
            result = {'id': 1};
          default:
            throw StateError('未预期调用 ${r.url.path}');
        }
        return http.Response(
          jsonEncode(result),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    )..token = 't';
    await tester.pumpWidget(
      MaterialApp(
        home: RequestFormPage(api: api, initialKind: 'CORRECTION'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('5 个自然日'), findsOneWidget);
    expect(find.text('补卡类型'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, '申请理由'), '忘记打卡');
    final cc = find.textContaining('抄送同事（已选');
    await tester.scrollUntilVisible(
      cc,
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(cc);
    await tester.pumpAndSettle();
    await tester.tap(find.text('李同事（E002）'));
    await tester.tap(find.text('完成'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('确认提交'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('确认提交'));
    await tester.pumpAndSettle();
    expect(submitted?['kind'], 'CORRECTION');
    expect(submitted?['punchKind'], 'NORMAL');
    expect(submitted?['ccUserIds'], [2]);
    expect(submitted?.containsKey('latitude'), false);
    expect(submitted?.containsKey('endAt'), false);
    expect(tester.takeException(), isNull);
  });

  testWidgets('补卡详情允许审批及待批撤回；批准后没有撤销按钮', (tester) async {
    var approved = false;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path.endsWith('/decision')) {
          expect(jsonDecode(r.body)['approve'], true);
          approved = true;
          return http.Response('{}', 200);
        }
        return http.Response(
          jsonEncode({
            'id': 1,
            'kind': 'CORRECTION',
            'status': approved ? 'APPROVED' : 'PENDING',
            'user_id': 2,
            'user_name': '员工',
            'start_at': '2026-11-10T10:21:00+08:00',
            'punch_kind': 'FIELD',
            'current_step': 1,
            'reason': '现场漏打',
            'steps': [
              {
                'step': 1,
                'approver_id': 1,
                'approver_name': '主管',
                'status': approved ? 'APPROVED' : 'PENDING',
              },
            ],
            'cc': [],
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    )..token = 't';
    await tester.pumpWidget(
      MaterialApp(
        home: RequestDetailPage(api: api, id: 1, user: {'id': 1}),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('类型：外勤'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('提交审批'),
      250,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.text('提交审批'));
    await tester.pumpAndSettle();
    expect(approved, true);
    expect(find.text('撤销申请'), findsNothing);
    await tester.pumpWidget(
      MaterialApp(
        home: RequestDetailPage(
          key: const ValueKey('owner'),
          api: api,
          id: 1,
          user: {'id': 2},
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('撤销申请'), findsNothing);
    approved = false;
    await tester.pumpWidget(
      MaterialApp(
        home: RequestDetailPage(
          key: const ValueKey('pending'),
          api: api,
          id: 1,
          user: {'id': 2},
        ),
      ),
    );
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(
      find.text('撤回申请'),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('撤回申请'), findsOneWidget);
  });

  testWidgets('个人中心刷新显示加班批准后的精确调休余额', (tester) async {
    num amount = 0;
    final api = Api(
      client: MockClient((r) async {
        final dynamic result;
        if (r.url.path == '/api/profile') {
          result = {
            'name': '员工',
            'employee_no': 'E002',
            'department_name': '研发',
            'roles': 'EMPLOYEE',
          };
        } else if (r.url.path == '/api/balances') {
          result = [
            {'type_name': '调休', 'year': 2026, 'amount': amount},
          ];
        } else {
          throw StateError('未预期调用 ${r.url.path}');
        }
        return http.Response(
          jsonEncode(result),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    )..token = 't';
    await tester.pumpWidget(
      MaterialApp(
        home: ProfilePage(api: api, onLogout: () async {}, onExpired: () {}),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('0 天'), findsOneWidget);
    amount = 0.625;
    await tester.tap(find.byTooltip('刷新余额'));
    await tester.pumpAndSettle();
    expect(find.text('0.625 天'), findsOneWidget);
    expect(find.text('0 天'), findsNothing);
    expect(tester.takeException(), isNull);
  });
}
