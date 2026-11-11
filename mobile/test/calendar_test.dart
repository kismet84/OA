import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/calendar.dart';
import 'package:oa_employee/pages.dart';

http.Response response(Object value, [int status = 200]) => http.Response(
  jsonEncode(value),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
Finder input(String label) => find.byWidgetPredicate(
  (w) => w is TextField && w.decoration?.labelText == label,
);
Json detail({bool cancelled = false}) => {
  'id': 7,
  'title': '评审会议',
  'start_at': '2031-02-03T09:00:00',
  'end_at': '2031-02-03T10:00:00',
  'room_id': 1,
  'room_name': '一号会议室',
  'organizer_id': 1,
  'organizer_name': '组织者',
  'description': '项目说明',
  'cancelled_at': cancelled ? '2031-02-01T10:00:00' : null,
  'canEdit': false,
  'canReply': !cancelled,
  'attendees': [
    {'user_id': 2, 'name': '参会人', 'employee_no': 'E2', 'response': 'PENDING'},
  ],
};
void main() {
  testWidgets('创建会议选择占用、邀请同事，409保留填写内容且展示中文原因', (tester) async {
    tester.view.physicalSize = const Size(1200, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    Json? sent;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/meeting-rooms') {
          return response([
            {'id': 1, 'name': '一号会议室', 'capacity': 2},
          ]);
        }
        if (r.url.path == '/api/colleagues') {
          return response([
            {'id': 2, 'name': '同事', 'employee_no': 'E2'},
          ]);
        }
        if (r.method == 'POST') {
          sent = jsonDecode(r.body) as Json;
          return response({'message': '会议室时段冲突：评审会议'}, 409);
        }
        if (r.url.path == '/api/meetings') {
          return response([
            {
              'id': 3,
              'title': '已占用会议',
              'organizer_name': '另一位同事',
              'start_at': '2031-02-03T09:00:00',
              'end_at': '2031-02-03T10:00:00',
            },
          ]);
        }
        throw StateError(r.url.toString());
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: MeetingPage(api: api, user: {'id': 1, 'name': '组织者'}),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(input('会议标题'), '新项目会议');
    await tester.enterText(input('开始时间（YYYY-MM-DDTHH:mm）'), '2031-02-03T09:00');
    await tester.enterText(input('结束时间（YYYY-MM-DDTHH:mm）'), '2031-02-03T10:00');
    await tester.tap(find.byType(DropdownButtonFormField<int>));
    await tester.pumpAndSettle();
    await tester.tap(find.textContaining('一号会议室').last);
    await tester.pumpAndSettle();
    expect(find.textContaining('已占用会议'), findsOneWidget);
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pumpAndSettle();
    await tester.tap(find.text('保存会议'));
    await tester.pumpAndSettle();
    expect(sent?['roomId'], 1);
    expect(sent?['attendeeIds'], [2]);
    expect(sent?['startAt'], '2031-02-03T09:00');
    expect(find.text('会议室时段冲突：评审会议'), findsOneWidget);
    expect(find.text('新项目会议'), findsOneWidget);
  });
  testWidgets('系统通知进入会议，可请假并提交原因；取消后不可再回复', (tester) async {
    tester.view.physicalSize = const Size(1200, 1800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    Json? reply;
    bool cancelled = false;
    final api = Api(
      client: MockClient((r) async {
        if (r.url.path == '/api/messages') {
          return response([
            {'id': 9, 'meeting_id': 7, 'message': '会议邀请', 'read_at': null},
          ]);
        }
        if (r.url.path == '/api/notifications/9/read') {
          return response({'message': '已读'});
        }
        if (r.url.path == '/api/meetings/7/reply') {
          reply = jsonDecode(r.body) as Json;
          cancelled = true;
          return response({'message': '已保存'});
        }
        if (r.url.path == '/api/meetings/7') {
          return response(detail(cancelled: cancelled));
        }
        throw StateError(r.url.toString());
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: MessagePage(api: api, user: {'id': 2}, initial: 'notifications'),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('会议邀请'));
    await tester.pumpAndSettle();
    expect(find.text('评审会议'), findsOneWidget);
    await tester.enterText(input('请假原因（可选）'), '已有客户安排');
    await tester.tap(find.text('请假'));
    await tester.pumpAndSettle();
    expect(reply, {'response': 'DECLINED', 'reason': '已有客户安排'});
    expect(find.text('已取消'), findsOneWidget);
    expect(find.text('接受邀请'), findsNothing);
    expect(find.text('请假'), findsNothing);
  });
  testWidgets('我的日程查询按上海日期且默认不含取消会议', (tester) async {
    Uri? request;
    final api = Api(
      client: MockClient((r) async {
        request = r.url;
        return response([]);
      }),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: CalendarPage(api: api, user: {'id': 1}),
      ),
    );
    await tester.pumpAndSettle();
    expect(request?.queryParameters['mine'], 'true');
    expect(request?.queryParameters['includeCancelled'], 'false');
    expect(request?.queryParameters['from'], '${shanghaiDay()}T00:00:00');
    expect(find.text('当前日期范围没有会议'), findsOneWidget);
  });
}
