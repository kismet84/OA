import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/main.dart';
import 'package:oa_employee/pages.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });
  testWidgets('恢复登录后显示待办与未读汇总和五个入口', (tester) async {
    final api = Api(
      client: MockClient((r) async {
        expect(r.url.path, '/api/messages/summary');
        expect(r.headers['Authorization'], 'Bearer test-token');
        return http.Response(
          jsonEncode({'pending': 3, 'notifications': 2, 'announcements': 1}),
          200,
        );
      }),
    )..token = 'test-token';
    await tester.pumpWidget(
      MaterialApp(
        home: HomePage(
          api: api,
          user: {'id': 1, 'name': '张员工'},
          onLogout: () async {},
          onExpired: () {},
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('张员工，你好'), findsOneWidget);
    expect(find.text('3'), findsOneWidget);
    for (final label in [
      '待我审批',
      '未读通知',
      '未读公告',
      '打卡',
      '审批与申请',
      '消息中心',
      '通讯录',
      '个人中心',
    ]) {
      expect(find.text(label), findsOneWidget);
    }
    expect(tester.takeException(), isNull);
  });
  testWidgets('首次登录强制改密且没有工作台入口', (tester) async {
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/auth/password') {
          expect(jsonDecode(r.body), {
            'oldPassword': 'Initial@2026',
            'newPassword': 'Changed@2026',
          });
          expect(r.headers['Authorization'], 'Bearer t');
          return http.Response('{}', 200);
        }
        expect(r.url.path, '/api/auth/login');
        return http.Response(
          jsonEncode({
            'token': 't',
            'user': {'id': 1, 'name': '新员工', 'mustChange': true},
          }),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );
    await tester.pumpWidget(EmployeeApp(api: api, restoreSession: false));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, '工号或手机号'), 'E001');
    await tester.enterText(
      find.widgetWithText(TextField, '密码'),
      'Initial@2026',
    );
    await tester.tap(find.widgetWithText(FilledButton, '登录'));
    await tester.pumpAndSettle();
    expect(find.text('首次登录 · 修改密码'), findsOneWidget);
    expect(find.text('工作台'), findsNothing);
    expect(find.text('修改并重新登录'), findsOneWidget);
    final passwordPage = tester.widget<PopScope>(find.byType(PopScope).last);
    expect(passwordPage.canPop, false);
    await tester.enterText(
      find.widgetWithText(TextField, '原密码'),
      'Initial@2026',
    );
    await tester.enterText(
      find.widgetWithText(TextField, '新密码'),
      'Changed@2026',
    );
    await tester.enterText(
      find.widgetWithText(TextField, '再次输入新密码'),
      'Changed@2026',
    );
    await tester.tap(find.widgetWithText(FilledButton, '修改并重新登录'));
    await tester.pumpAndSettle();
    expect(find.widgetWithText(FilledButton, '登录'), findsOneWidget);
    expect(find.text('工作台'), findsNothing);
    expect(api.token, isNull);
    expect(tester.takeException(), isNull);
  });
  testWidgets('消息加载失败显示中文原因并可重试', (tester) async {
    var calls = 0;
    final api = Api(
      client: MockClient((r) async {
        calls++;
        return calls == 1
            ? http.Response(
                '{"message":"服务暂不可用"}',
                503,
                headers: {'content-type': 'application/json; charset=utf-8'},
              )
            : http.Response(
                '{"pending":0,"notifications":0,"announcements":0}',
                200,
              );
      }),
    )..token = 't';
    await tester.pumpWidget(
      MaterialApp(
        home: HomePage(
          api: api,
          user: {'id': 1, 'name': '员工'},
          onLogout: () async {},
          onExpired: () {},
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('服务暂不可用'), findsOneWidget);
    await tester.tap(find.byTooltip('刷新'));
    await tester.pumpAndSettle();
    expect(find.text('服务暂不可用'), findsNothing);
    expect(calls, 2);
  });
}
