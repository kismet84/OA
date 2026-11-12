import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/assets.dart';
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
  t.view.physicalSize = const Size(1200, 2200);
  t.view.devicePixelRatio = 1;
  addTearDown(t.view.resetPhysicalSize);
  addTearDown(t.view.resetDevicePixelRatio);
}

Json detail({
  String status = 'IN_USE',
  bool requested = false,
  bool approve = false,
}) => {
  'id': 8,
  'category_name': '笔记本',
  'kind': 'ITEM',
  'user_name': '员工',
  'status': status,
  'quantity': 1,
  'purpose': '办公',
  'expected_date': '2032-03-01',
  'needs_return': true,
  'isOwner': !approve,
  'canApprove': approve,
  'return_requested': requested,
  'assignments': [
    {'asset_id': 1, 'code': 'PC-01', 'name': '办公电脑', 'returned_at': null},
  ],
};
void main() {
  for (final item in [true, false]) {
    testWidgets('${item ? '按件默认归还' : '消耗品无需归还'}，必填校验、冲突保留表单', (t) async {
      large(t);
      Json? sent;
      final api = Api(
        client: MockClient((r) async {
          if (r.method == 'GET') {
            return response([
              {'id': 1, 'kind': 'ITEM', 'name': '笔记本', 'available': 2},
              {
                'id': 2,
                'kind': 'CONSUMABLE',
                'name': '鼠标',
                'available': 3,
                'unit': '个',
              },
            ]);
          }
          sent = jsonDecode(r.body) as Json;
          return response({'message': '模拟冲突，请重试'}, 409);
        }),
      );
      await t.pumpWidget(MaterialApp(home: AssetApplyPage(api: api)));
      await t.pumpAndSettle();
      await t.tap(find.text('提交领用申请'));
      await t.pumpAndSettle();
      expect(find.text('请选择资产'), findsOneWidget);
      expect(sent, isNull);
      await t.tap(find.byType(DropdownButtonFormField<String>));
      await t.pumpAndSettle();
      await t.tap(find.text(item ? '笔记本 · 可用 2 件' : '鼠标 · 可用 3 个').last);
      await t.pumpAndSettle();
      await t.enterText(input('用途'), '办公使用');
      await t.enterText(input('期望领用日期（YYYY-MM-DD）'), '2032-03-01');
      if (item) {
        expect(
          t.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
          isTrue,
        );
        await t.enterText(input('预计归还日期（YYYY-MM-DD，可选）'), '2032-04-01');
      } else {
        expect(find.byType(CheckboxListTile), findsNothing);
      }
      await t.tap(find.text('提交领用申请'));
      await t.pumpAndSettle();
      expect(sent?['needsReturn'], item);
      expect(sent?[item ? 'categoryId' : 'consumableId'], item ? 1 : 2);
      expect(sent?['returnDate'], item ? '2032-04-01' : null);
      expect(sent?['expectedDate'], '2032-03-01');
      expect(find.text('模拟冲突，请重试'), findsOneWidget);
      expect(find.text('办公使用'), findsOneWidget);
    });
  }
  testWidgets('我的资产归还后等待行政确认，重复提交入口消失', (t) async {
    large(t);
    bool requested = false;
    Json? sent;
    final api = Api(
      client: MockClient((r) async {
        if (r.method == 'POST') {
          expect(r.url.path, '/api/assets/requests/8/return');
          sent = jsonDecode(r.body) as Json;
          requested = true;
          return response({});
        }
        if (r.url.path == '/api/assets/mine') {
          return response([
            {
              'request_id': 8,
              'code': 'PC-01',
              'name': '办公电脑',
              'returned_at': null,
            },
          ]);
        }
        if (r.url.path == '/api/assets/requests') {
          return response([]);
        }
        return response(detail(requested: requested));
      }),
    );
    await t.pumpWidget(MaterialApp(home: AssetsPage(api: api)));
    await t.pumpAndSettle();
    await t.tap(find.text('我的资产'));
    await t.pumpAndSettle();
    expect(find.textContaining('当前在我名下'), findsOneWidget);
    await t.tap(find.text('PC-01 · 办公电脑'));
    await t.pumpAndSettle();
    await t.enterText(input('处理说明（驳回必填）'), '员工归还电脑');
    await t.tap(find.text('发起归还登记'));
    await t.pumpAndSettle();
    expect(sent?['note'], '员工归还电脑');
    expect(find.text('已登记归还，等待行政确认'), findsOneWidget);
    expect(find.text('发起归还登记'), findsNothing);
  });
  testWidgets('资产待办与任务审批并存，上级审批后刷新原列表', (t) async {
    large(t);
    bool approved = false;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/messages') {
          return response([
            if (!approved)
              {
                'id': 8,
                'item_type': 'ASSET',
                'title': '资产领用',
                'status': 'PENDING',
                'user_name': '员工',
              },
            {
              'id': 8,
              'item_type': 'TASK',
              'task_id': 8,
              'title': '原任务',
              'status': 'TODO',
            },
            {
              'id': 8,
              'item_type': 'REQUEST',
              'kind': 'OUTING',
              'user_name': '员工',
              'status': 'PENDING',
            },
          ]);
        }
        if (r.url.path == '/api/assets/requests/8/approve') {
          expect(jsonDecode(r.body)['approved'], isTrue);
          approved = true;
          return response({});
        }
        if (r.url.path == '/api/assets/requests/8') {
          // The supervisor may only read pending applications.
          if (approved) return response({'message': '无权查看或处理该领用申请'}, 403);
          return response(
            detail(status: approved ? 'READY' : 'PENDING', approve: !approved),
          );
        }
        return response({
          'pending': approved ? 2 : 3,
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
    expect(find.textContaining('原任务'), findsOneWidget);
    await t.tap(find.textContaining('资产领用 ·'));
    await t.pumpAndSettle();
    await t.tap(find.text('同意领用'));
    await t.pumpAndSettle();
    expect(approved, isTrue);
    expect(find.text('同意领用'), findsNothing);
    expect(find.textContaining('资产领用 ·'), findsNothing);
    expect(find.textContaining('原任务'), findsOneWidget);
  });
}
