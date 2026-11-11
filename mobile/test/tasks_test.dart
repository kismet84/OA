import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/tasks.dart';
import 'package:oa_employee/pages.dart';

http.Response response(Object data, [int status = 200]) => http.Response(
  jsonEncode(data),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
Finder input(String label) => find.byWidgetPredicate(
  (w) => w is TextField && w.decoration?.labelText == label,
);
Json task(String status, List<Json> followups) => {
  'id': 7,
  'title': '交付任务',
  'creator_name': '发起人',
  'status': status,
  'due_at': '2001-01-01T23:59:59',
  'overdue': status != 'COMPLETED',
  'canEdit': false,
  'canRestore': false,
  'assignees': [
    {'user_id': 2, 'name': '同事'},
  ],
  'followups': followups,
};
void large(WidgetTester tester) {
  tester.view.physicalSize = const Size(1200, 1800);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}

void main() {
  testWidgets('创建任务必须选负责人，错误保留填写，发送上海截止和去重名单', (tester) async {
    large(tester);
    Json? sent;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/colleagues') {
          return response([
            {'id': 2, 'name': '同事', 'employee_no': 'E2'},
          ]);
        }
        sent = jsonDecode(r.body) as Json;
        return response({'message': '负责人必须为在职员工'}, 400);
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: TaskPage(api: api, user: {'id': 1, 'name': '发起人'}),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(input('任务标题'), '交付任务');
    await tester.tap(find.text('保存任务'));
    await tester.pumpAndSettle();
    expect(find.text('请选择1至200名负责人'), findsOneWidget);
    expect(sent, isNull);
    await tester.tap(find.widgetWithText(CheckboxListTile, '同事 · E2'));
    await tester.enterText(
      input('截止日期或时间（YYYY-MM-DD 或 YYYY-MM-DDTHH:mm，可选）'),
      '2031-02-03',
    );
    await tester.tap(find.text('保存任务'));
    await tester.pumpAndSettle();
    expect(sent?['assigneeIds'], [2]);
    expect(sent?['dueAt'], '2031-02-03');
    expect(find.text('负责人必须为在职员工'), findsOneWidget);
    expect(find.text('交付任务'), findsOneWidget);
  });
  testWidgets('待我审批混排：任务跟进、完成后刷新离开；审批继续打开原详情', (tester) async {
    large(tester);
    String status = 'TODO';
    final followups = <Json>[];
    bool requestOpened = false;
    Json? completed;
    final api = Api(
      client: MockClient((r) async {
        final path = r.url.path;
        if (path == '/api/messages') {
          return response([
            if (status != 'COMPLETED')
              {
                'id': 7,
                'task_id': 7,
                'item_type': 'TASK',
                'title': '交付任务',
                'status': status,
                'overdue': true,
              },
            {
              'id': 7,
              'item_type': 'REQUEST',
              'kind': 'OUTING',
              'user_name': '申请人',
              'status': 'PENDING',
            },
          ]);
        }
        if (path == '/api/tasks/7/followups') {
          followups.add({
            'content': (jsonDecode(r.body) as Json)['content'],
            'user_name': '同事',
          });
          return response({});
        }
        if (path == '/api/tasks/7/status') {
          completed = jsonDecode(r.body) as Json;
          status = completed!['status'];
          return response({});
        }
        if (path == '/api/tasks/7') return response(task(status, followups));
        if (path == '/api/requests/7') {
          requestOpened = true;
          return response({
            'id': 7,
            'kind': 'OUTING',
            'user_name': '申请人',
            'status': 'PENDING',
            'steps': [],
            'cc': [],
            'items': [],
            'attachments': [],
            'payment_history': [],
          });
        }
        throw StateError(path);
      }),
    )..token = 'test';
    await tester.pumpWidget(
      MaterialApp(
        home: MessagePage(api: api, user: {'id': 2}, initial: 'pending'),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('任务 · 交付任务'), findsOneWidget);
    expect(find.text('审批单 · 外出 · 申请人'), findsOneWidget);
    await tester.tap(find.text('任务 · 交付任务'));
    await tester.pumpAndSettle();
    expect(find.textContaining('已过期'), findsOneWidget);
    await tester.enterText(input('跟进说明（完成时建议填写）'), '已联系同事');
    await tester.tap(find.text('追加跟进'));
    await tester.pumpAndSettle();
    expect(find.text('已联系同事'), findsOneWidget);
    await tester.tap(find.text('标记已完成'));
    await tester.pumpAndSettle();
    expect(completed?['status'], 'COMPLETED');
    expect(find.text('开始处理'), findsNothing);
    expect(find.text('标记已完成'), findsNothing);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('任务 · 交付任务'), findsNothing);
    await tester.tap(find.text('审批单 · 外出 · 申请人'));
    await tester.pumpAndSettle();
    expect(requestOpened, isTrue);
  });
  testWidgets('系统通知跳任务；取消任务无跟进、编辑和恢复按钮', (tester) async {
    large(tester);
    bool read = false;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/messages') {
          return response([
            {'id': 9, 'task_id': 7, 'message': '任务已取消'},
          ]);
        }
        if (r.url.path == '/api/notifications/9/read') {
          read = true;
          return response({});
        }
        return response(task('CANCELLED', []));
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: MessagePage(api: api, user: {'id': 2}),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('任务已取消'));
    await tester.pumpAndSettle();
    expect(read, isTrue);
    expect(find.text('交付任务'), findsOneWidget);
    expect(find.text('追加跟进'), findsNothing);
    expect(find.text('修改任务'), findsNothing);
    expect(find.text('恢复为待处理'), findsNothing);
  });
}
