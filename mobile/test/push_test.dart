import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/material.dart';
import 'package:oa_employee/main.dart';
import 'package:oa_employee/pages.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/push.dart';

class FakeDevice implements PushDevice {
  bool allowed = true;
  bool initial = false;
  bool fails = false;
  String value = 'first';
  final tokens = StreamController<String>.broadcast(sync: true);
  final messages = StreamController<void>.broadcast(sync: true);
  final taps = StreamController<void>.broadcast(sync: true);
  @override
  Future<void> initialize() async {
    if (fails) throw StateError('Firebase 不可用');
  }

  @override
  Future<bool> permission() async => allowed;
  @override
  Future<String?> token() async => value;
  @override
  Stream<String> get refreshed => tokens.stream;
  @override
  Stream<void> get foreground => messages.stream;
  @override
  Stream<void> get opened => taps.stream;
  @override
  Future<bool> initialOpen() async => initial;
}

void main() {
  late FakeDevice device;
  late Api api;
  late PushRegistration push;
  late List<http.Request> requests;
  int foreground = 0, opened = 0;
  setUp(() {
    SharedPreferences.setMockInitialValues({});
    device = FakeDevice();
    requests = [];
    foreground = opened = 0;
    api = Api(
      client: MockClient((r) async {
        requests.add(r);
        return http.Response('{}', 200);
      }),
    )..token = 'session';
    push = PushRegistration(
      api,
      device,
      onForeground: () {
        foreground++;
      },
      onOpen: () {
        opened++;
      },
    );
  });
  tearDown(() async {
    push.dispose();
    await device.tokens.close();
    await device.messages.close();
    await device.taps.close();
    api.client.close();
  });
  testWidgets('登录上报、前台提示、点击进入消息中心和退出注销的真实组件链路', (tester) async {
    push.dispose();
    api.client.close();
    final calls = <String>[];
    api = Api(
      client: MockClient((r) async {
        calls.add('${r.method} ${r.url.path}');
        Object body = {};
        if (r.url.path == '/api/auth/login') {
          body = {
            'token': 'session',
            'user': {'id': 7, 'name': '推送员工', 'mustChange': false},
          };
        } else if (r.url.path == '/api/messages/summary') {
          body = {'pending': 0, 'notifications': 1, 'announcements': 0};
        } else if (r.url.path == '/api/workbench/summary') {
          body = {
            'attendance': {
              'normalPunches': 0,
              'fieldPunches': 0,
              'recordedDays': 0,
            },
            'approvals': {'completed': 0},
          };
        } else if (r.url.path == '/api/messages') {
          body = [];
        }
        return http.Response(
          jsonEncode(body),
          200,
          headers: {'content-type': 'application/json; charset=utf-8'},
        );
      }),
    );
    await tester.pumpWidget(
      EmployeeApp(api: api, restoreSession: false, pushDevice: device),
    );
    await tester.pumpAndSettle();
    await tester.enterText(
      find.widgetWithText(TextField, '工号或手机号'),
      'employee',
    );
    await tester.enterText(
      find.widgetWithText(TextField, '密码'),
      'Push@Test2026',
    );
    await tester.tap(find.widgetWithText(FilledButton, '登录'));
    await tester.pumpAndSettle();
    expect(calls, contains('POST /api/push/tokens'));
    device.messages.add(null);
    await tester.pumpAndSettle();
    expect(find.text('收到新的系统通知，请查看消息中心'), findsOneWidget);
    await tester.tap(find.text('查看'));
    await tester.pumpAndSettle();
    expect(find.byType(MessagePage), findsOneWidget);
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    device.taps.add(null);
    await tester.pumpAndSettle();
    expect(find.byType(MessagePage), findsOneWidget);
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    await tester.widget<HomePage>(find.byType(HomePage)).onLogout();
    await tester.pumpAndSettle();
    expect(
      calls.indexOf('DELETE /api/push/tokens'),
      lessThan(calls.indexOf('POST /api/auth/logout')),
    );
    expect(find.widgetWithText(FilledButton, '登录'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox());
  });
  test('登录登记、刷新换 token、退出注销，退出后不再登记', () async {
    await push.start();
    expect(requests.single.method, 'POST');
    expect(jsonDecode(requests.single.body), {
      'token': 'first',
      'platform': 'ANDROID',
      'deviceName': '安卓员工端',
    });
    expect(requests.single.headers['Authorization'], 'Bearer session');
    device.tokens.add('second');
    await Future<void>.delayed(Duration.zero);
    expect(requests.map((r) => r.method), ['POST', 'POST', 'DELETE']);
    expect(jsonDecode(requests[2].body)['token'], 'first');
    await push.logout();
    expect(jsonDecode(requests.last.body)['token'], 'second');
    device.tokens.add('late');
    await Future<void>.delayed(Duration.zero);
    expect(requests.length, 4);
  });
  test('拒绝权限或 Firebase 故障不阻断登录、不上报 token', () async {
    device.allowed = false;
    await push.start();
    device.tokens.add('denied');
    await Future<void>.delayed(Duration.zero);
    expect(requests, isEmpty);
    push.dispose();
    device.fails = true;
    push = PushRegistration(api, device, onForeground: () {}, onOpen: () {});
    await push.start();
    expect(requests, isEmpty);
  });
  test('前台提示、后台点击和冷启动点击均交给消息中心入口', () async {
    device.initial = true;
    await push.start();
    expect(opened, 1);
    device.messages.add(null);
    device.taps.add(null);
    expect(foreground, 1);
    expect(opened, 2);
    push.deactivate();
    device.messages.add(null);
    device.taps.add(null);
    expect(foreground, 1);
    expect(opened, 2);
  });
  test('退出等待正在登记结束，然后注销，防止登记与退出竞态', () async {
    final gate = Completer<void>();
    api.client.close();
    api = Api(
      client: MockClient((r) async {
        requests.add(r);
        if (r.method == 'POST') await gate.future;
        return http.Response('{}', 200);
      }),
    )..token = 'session';
    push.dispose();
    push = PushRegistration(api, device, onForeground: () {}, onOpen: () {});
    final starting = push.start();
    await Future<void>.delayed(Duration.zero);
    final leaving = push.logout();
    gate.complete();
    await starting;
    await leaving;
    expect(requests.map((r) => r.method), ['POST', 'DELETE']);
  });
  test('服务端失败被隔离，可在下次登录重试', () async {
    api.client.close();
    var fail = true;
    api = Api(
      client: MockClient((r) async {
        requests.add(r);
        return http.Response('{}', fail ? 503 : 200);
      }),
    )..token = 'session';
    push.dispose();
    push = PushRegistration(api, device, onForeground: () {}, onOpen: () {});
    await push.start();
    fail = false;
    await push.start();
    expect(requests.length, 2);
  });
}
