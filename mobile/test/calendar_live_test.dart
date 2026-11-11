import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:oa_employee/api.dart';

void main() {
  final login = Platform.environment['OA_TEST_LOGIN'];
  final password = Platform.environment['OA_TEST_PASSWORD'];
  final guestLogin = Platform.environment['OA_TEST_MANAGER'];
  final roomId = int.tryParse(
    Platform.environment['OA_TEST_CALENDAR_ROOM_ID'] ?? '',
  );
  test(
    '真实Flutter API：预定、冲突、通知、回复、改期重置、取消后重订',
    () async {
      final owner = Api(), guest = Api();
      int? id, rebook;
      try {
        final user = await owner.login(
          login!,
          password!,
          '日程Flutter验收',
          'FlutterTest',
        );
        final participant = await guest.login(
          guestLogin!,
          password,
          '参会Flutter验收',
          'FlutterTest',
        );
        final day = DateTime.now().toUtc().add(
          const Duration(days: 700, hours: 8),
        );
        final date = day.toIso8601String().substring(0, 10);
        final body = <String, dynamic>{
          'title': 'Flutter日程验收${DateTime.now().millisecondsSinceEpoch}',
          'startAt': '${date}T09:00:00',
          'endAt': '${date}T10:00:00',
          'roomId': roomId,
          'description': 'Flutter员工端验收',
          'attendeeIds': [participant['id']],
        };
        id =
            (await owner.call('/meetings', method: 'POST', data: body))['id']
                as int;
        final detail = await owner.call('/meetings/$id') as Json;
        expect(detail['organizer_id'], user['id']);
        expect(detail['room_id'], roomId);
        await expectLater(
          guest.call('/meetings', method: 'POST', data: body),
          throwsA(isA<ApiError>().having((e) => e.status, '冲突状态', 409)),
        );
        final notifications =
            await guest.call('/messages?category=notifications') as List;
        expect(notifications.any((n) => n['meeting_id'] == id), true);
        await guest.call(
          '/meetings/$id/reply',
          method: 'POST',
          data: {'response': 'DECLINED', 'reason': '需协调时间'},
        );
        expect(
          (await owner.call('/meetings/$id'))['attendees'][0]['response'],
          'DECLINED',
        );
        body['startAt'] = '${date}T10:00:00';
        body['endAt'] = '${date}T11:00:00';
        await owner.call('/meetings/$id', method: 'PUT', data: body);
        expect(
          (await owner.call('/meetings/$id'))['attendees'][0]['response'],
          'PENDING',
        );
        await guest.call(
          '/meetings/$id/reply',
          method: 'POST',
          data: {'response': 'ACCEPTED'},
        );
        await owner.call('/meetings/$id/cancel', method: 'POST');
        await expectLater(
          guest.call(
            '/meetings/$id/reply',
            method: 'POST',
            data: {'response': 'ACCEPTED'},
          ),
          throwsA(isA<ApiError>().having((e) => e.status, '已取消', 400)),
        );
        rebook =
            (await owner.call('/meetings', method: 'POST', data: body))['id']
                as int;
        expect(rebook, isNot(id));
      } finally {
        for (final meeting in [id, rebook]) {
          if (meeting != null && owner.token != null) {
            final d = await owner.call('/meetings/$meeting');
            if (d['cancelled_at'] == null) {
              await owner.call('/meetings/$meeting/cancel', method: 'POST');
            }
          }
        }
        for (final api in [owner, guest]) {
          if (api.token != null) await api.call('/auth/logout', method: 'POST');
          api.client.close();
        }
      }
    },
    skip:
        login == null ||
            password == null ||
            guestLogin == null ||
            roomId == null
        ? '需专用员工/上级及 OA_TEST_CALENDAR_ROOM_ID'
        : false,
  );
}
