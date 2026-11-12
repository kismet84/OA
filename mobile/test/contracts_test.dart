import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/contracts.dart';
import 'package:oa_employee/pages.dart';

http.Response response(Object data, [int status = 200]) => http.Response(
  jsonEncode(data),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
Finder input(String label) => find.byWidgetPredicate(
  (w) => w is TextField && w.decoration?.labelText == label,
);
void large(WidgetTester t) {
  t.view.physicalSize = const Size(1200, 2400);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.resetPhysicalSize);
  addTearDown(t.view.resetDevicePixelRatio);
}

Json detail(String status, {bool approve = false}) => {
  'id': 8,
  'code': 'HT-2032-01',
  'name': '服务合同',
  'type': 'SERVICE',
  'status': status,
  'workflow_status': status,
  'isOwner': !approve,
  'canApprove': approve,
  'canArchive': !approve,
  'files': [],
  'steps': [],
};
void main() {
  testWidgets('合同登记必填、重复编号保留表单、无固定金额与长期数据', (t) async {
    large(t);
    Json? sent;
    final api = Api(
      client: MockClient((r) async {
        sent = jsonDecode(r.body) as Json;
        return response({'message': '合同编号重复'}, 409);
      }),
    );
    await t.pumpWidget(MaterialApp(home: ContractEditPage(api: api)));
    await t.pumpAndSettle();
    await t.tap(find.text('保存草稿'));
    await t.pumpAndSettle();
    expect(find.text('请填写合同名称'), findsOneWidget);
    expect(sent, isNull);
    for (final e in {
      'name': '服务合同',
      'counterparty': '测试单位',
      'signedDate': '2032-01-01',
      'effectiveDate': '2032-01-01',
    }.entries) {
      await t.enterText(input(contractFields[e.key]!), e.value);
    }
    await t.tap(find.text('保存草稿'));
    await t.pumpAndSettle();
    expect(sent?['amount'], isNull);
    expect(sent?['expiryDate'], isNull);
    expect(sent?['type'], 'SERVICE');
    expect(find.text('合同编号重复'), findsOneWidget);
    expect(find.text('服务合同'), findsOneWidget);
  });
  testWidgets('审批中可撤回，撤回后恢复修改提交入口', (t) async {
    large(t);
    String status = 'PENDING';
    final api = Api(
      client: MockClient((r) async {
        if (r.method == 'POST') {
          expect(r.url.path, '/api/contracts/8/withdraw');
          status = 'WITHDRAWN';
          return response({});
        }
        return response(detail(status));
      }),
    );
    await t.pumpWidget(MaterialApp(home: ContractDetailPage(api: api, id: 8)));
    await t.pumpAndSettle();
    expect(find.text('修改合同'), findsNothing);
    await t.tap(find.text('撤回合同'));
    await t.pumpAndSettle();
    expect(find.text('修改合同'), findsOneWidget);
    expect(find.text('提交合同审批'), findsOneWidget);
    expect(find.text('撤回合同'), findsNothing);
  });
  testWidgets('合同待办打开审批，处理后原任务仍在并刷新计数', (t) async {
    large(t);
    bool approved = false;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/messages') {
          return response([
            if (!approved)
              {
                'id': 8,
                'item_type': 'CONTRACT',
                'title': '服务合同',
                'status': 'PENDING',
              },
            {
              'id': 9,
              'item_type': 'TASK',
              'title': '原任务',
              'task_id': 9,
              'status': 'TODO',
            },
          ]);
        }
        if (r.url.path.endsWith('/approve')) {
          expect(jsonDecode(r.body)['approved'], true);
          approved = true;
          return response({});
        }
        if (r.url.path == '/api/contracts/8') {
          return response(
            detail(approved ? 'TO_ARCHIVE' : 'PENDING', approve: !approved),
          );
        }
        return response({
          'pending': approved ? 1 : 2,
          'notifications': 0,
          'announcements': 0,
        });
      }),
    );
    api.token = 'test';
    await t.pumpWidget(
      MaterialApp(
        home: MessagePage(
          api: api,
          user: {'id': 1},
          approval: true,
          initial: 'pending',
        ),
      ),
    );
    await t.pumpAndSettle();
    await t.tap(find.textContaining('合同 ·'));
    await t.pumpAndSettle();
    await t.tap(find.text('同意合同'));
    await t.pumpAndSettle();
    expect(approved, true);
    expect(find.textContaining('待归档'), findsOneWidget);
    await t.pageBack();
    await t.pumpAndSettle();
    expect(find.textContaining('原任务'), findsOneWidget);
    expect(find.textContaining('合同 ·'), findsNothing);
  });
}
