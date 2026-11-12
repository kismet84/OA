import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/visitors.dart';

void main() {
  final fixture = Platform.environment['OA_VISITOR_FIXTURE'];
  test('真实Flutter接口：预约、我的访客、前台签到签离、通知及取消', () async {
    final data = jsonDecode(File(fixture!).readAsStringSync());
    final owner = Api()..token = data['owner']['token'];
    final admin = Api()..token = data['adminToken'];
    try {
      final now = DateTime.now().toUtc().add(const Duration(hours: 8));
      String wall(DateTime d) => d.toIso8601String().substring(0, 16);
      final body = visitorInput({
        'name': 'Flutter来访${now.microsecondsSinceEpoch}',
        'phone': '13812341234',
        'company': 'Flutter真实验收',
        'purpose': '来访交流',
        'people': '1',
        'startAt': wall(now),
        'endAt': wall(now.add(const Duration(hours: 2))),
      });
      final before = (await owner.call('/messages/summary'))['pending'];
      final r = await owner.call('/visitors', method: 'POST', data: body);
      expect(r['code'], matches(RegExp(r'^\d{6}$')));
      final rows = await owner.call('/visitors?mine=true') as List;
      expect(
        rows.any((v) => v['id'] == r['id'] && v['phone'] == '138****1234'),
        isTrue,
      );
      await admin.call(
        '/visitors/check-in',
        method: 'POST',
        data: {'code': r['code']},
      );
      expect((await owner.call('/visitors/${r['id']}'))['status'], 'ARRIVED');
      final notices =
          await owner.call('/messages?category=notifications') as List;
      expect(
        notices.where((v) => v['message'].contains(body['name'])).length,
        1,
      );
      await admin.call('/visitors/${r['id']}/check-out', method: 'POST');
      expect((await owner.call('/visitors/${r['id']}'))['status'], 'LEFT');
      final cancelled = await owner.call(
        '/visitors',
        method: 'POST',
        data: body,
      );
      await owner.call('/visitors/${cancelled['id']}/cancel', method: 'POST');
      expect(
        (await owner.call('/visitors/${cancelled['id']}'))['status'],
        'CANCELLED',
      );
      expect((await owner.call('/messages/summary'))['pending'], before);
    } finally {
      owner.client.close();
      admin.client.close();
    }
  }, skip: fixture == null ? '设置OA_VISITOR_FIXTURE为HTTP验收结果的绝对路径' : false);
}
