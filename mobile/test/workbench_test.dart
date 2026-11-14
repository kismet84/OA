import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/workbench.dart';

Json fixture({bool team = false, bool company = false}) => {
  'from': '2026-11-01',
  'to': '2026-11-30',
  'scope': 'self',
  'people': 1,
  'canViewTeam': team,
  'canFilterDepartment': company,
  'departments': [
    {'id': 2, 'name': '研发部'},
  ],
  'attendance': {
    'normalPunches': 251,
    'fieldPunches': 0,
    'recordedDays': 1,
    'approvedCorrections': 0,
    'leaveDays': 0.5,
    'travelDays': 0,
    'outingDays': 0,
    'overtimeDays': 1,
    'overtimeCreditedDays': 1.5,
    'daysByType': [],
  },
  'approvals': {
    'pendingForMe': 2,
    'initiatedPending': 3,
    'completed': 6,
    'averageHours': 48,
    'waiting': {
      'total': 3,
      'withinOneDay': 1,
      'oneToThreeDays': 1,
      'overThreeDays': 1,
    },
    'byType': [],
  },
};
http.Response response(Object d, [int code = 200]) => http.Response(
  jsonEncode(d),
  code,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
void main() {
  testWidgets('员工展示服务端数字且无团队筛选，日期错误保留输入与中文提示', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final queries = <Uri>[];
    final api = Api(
      client: MockClient((r) async {
        queries.add(r.url);
        return queries.length == 1
            ? response(fixture())
            : response({'message': '日期无效，请使用年月日格式'}, 400);
      }),
    );
    await tester.pumpWidget(MaterialApp(home: WorkbenchPage(api: api)));
    await tester.pumpAndSettle();
    expect(find.text('正常打卡次数 251'), findsOneWidget);
    expect(find.text('外勤打卡次数 0'), findsOneWidget);
    expect(find.textContaining('平均审批耗时 48 小时'), findsOneWidget);
    expect(find.byType(DropdownButtonFormField<String>), findsNothing);
    await tester.enterText(find.widgetWithText(TextField, '汇总开始日期'), 'bad');
    await tester.tap(find.text('查询汇总'));
    await tester.pumpAndSettle();
    expect(queries.last.queryParameters['from'], 'bad');
    expect(queries.last.queryParameters['scope'], 'self');
    expect(find.text('日期无效，请使用年月日格式'), findsOneWidget);
  });
  testWidgets('财务可选全公司及部门，查询条件发送服务端并复用打卡接口', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final queries = <Uri>[];
    final api = Api(
      client: MockClient((r) async {
        queries.add(r.url);
        if (r.url.path.endsWith('/members')) {
          return response([
            {'id': 3, 'name': '张员工', 'employee_no': 'E3'},
          ]);
        }
        if (r.url.path.endsWith('/punches')) return response([]);
        return response(fixture(team: true, company: true));
      }),
    );
    await tester.pumpWidget(MaterialApp(home: WorkbenchPage(api: api)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('本人').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('全公司').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('全公司').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('研发部').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('查询汇总'));
    await tester.pumpAndSettle();
    expect(queries.last.queryParameters['scope'], 'team');
    expect(queries.last.queryParameters['departmentId'], '2');
    await tester.tap(find.text('按员工查看打卡记录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('张员工（E3）'));
    await tester.pumpAndSettle();
    expect(queries.last.path, '/api/punches');
    expect(queries.last.queryParameters['userId'], '3');
    expect(find.text('暂无记录'), findsOneWidget);
  });
}
