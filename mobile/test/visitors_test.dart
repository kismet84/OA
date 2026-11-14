import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/visitors.dart';

Map<String, String> values() => {
  'name': '张访客',
  'phone': '13812341234',
  'company': '测试单位',
  'purpose': '交流',
  'people': '2',
  'startAt': '2032-01-01 09:00',
  'endAt': '2032-01-01 10:00',
};
http.Response reply(Object body, [int code = 200]) => http.Response(
  jsonEncode(body),
  code,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
void large(WidgetTester t) {
  t.view.physicalSize = const Size(1200, 2400);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.resetPhysicalSize);
  addTearDown(t.view.resetDevicePixelRatio);
}

void main() {
  test('预约字段、人数、手机号、无效日期和北京时间', () {
    final v = values();
    expect(visitorInput(v)['startAt'], '2032-01-01T09:00');
    expect(visitorInput(v)['people'], 2);
    for (final patch in [
      {'name': ''},
      {'phone': 'abc'},
      {'people': '101'},
      {'startAt': '2032-02-30 09:00'},
      {'endAt': v['startAt']!},
    ]) {
      expect(() => visitorInput({...v, ...patch}), throwsA(isA<ApiError>()));
    }
  });
  testWidgets('预约必填与冲突保留表单', (t) async {
    large(t);
    Json? sent;
    final api = Api(
      client: MockClient((r) async {
        sent = jsonDecode(r.body) as Json;
        return reply({'message': '有效访客码冲突，请稍后重试'}, 409);
      }),
    );
    await t.pumpWidget(MaterialApp(home: VisitorEditPage(api: api)));
    await t.tap(find.text('确认预约'));
    await t.pumpAndSettle();
    expect(find.text('请填写访客姓名'), findsOneWidget);
    expect(sent, isNull);
    for (final e in values().entries) {
      final finder = find.byWidgetPredicate(
        (w) =>
            w is TextField && w.decoration?.labelText == visitorFields[e.key],
      );
      await t.enterText(finder, e.value);
    }
    await t.tap(find.text('确认预约'));
    await t.pumpAndSettle();
    expect(sent?['people'], 2);
    expect(find.text('有效访客码冲突，请稍后重试'), findsOneWidget);
    expect(find.text('张访客'), findsOneWidget);
  });
  testWidgets('我的访客筛选及详情取消后显示已取消与访客码', (t) async {
    large(t);
    var state = 'WAITING';
    final api = Api(
      client: MockClient((r) async {
        final detail = {
          'id': 8,
          'name': '张访客',
          'status': state,
          'code': '001234',
          'isOwner': true,
          'phone': '138****1234',
        };
        if (r.url.path.endsWith('/cancel')) {
          state = 'CANCELLED';
          return reply({});
        }
        if (r.url.path == '/api/visitors') {
          expect(r.url.queryParameters['mine'], 'true');
          return reply([detail]);
        }
        return reply(detail);
      }),
    );
    api.token = 'test';
    await t.pumpWidget(MaterialApp(home: VisitorsPage(api: api)));
    await t.pumpAndSettle();
    final visitorTile = find.widgetWithText(ListTile, '张访客');
    expect(
      find.descendant(of: visitorTile, matching: find.text('待到访')),
      findsOneWidget,
    );
    await t.tap(visitorTile);
    await t.pumpAndSettle();
    expect(find.text('访客码：001234'), findsOneWidget);
    await t.tap(find.text('取消预约'));
    await t.pump(const Duration(milliseconds: 300));
    await t.tap(find.widgetWithText(FilledButton, '确认'));
    await t.pumpAndSettle();
    expect(find.text('张访客'), findsOneWidget);
    expect(find.text('已取消'), findsOneWidget);
    expect(find.text('取消预约'), findsNothing);
  });
}
