import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/validation.dart';

void main() {
  final login = Platform.environment['OA_TEST_LOGIN'];
  final password = Platform.environment['OA_TEST_PASSWORD'];
  final managerLogin = Platform.environment['OA_TEST_MANAGER'];
  test(
    '真实员工 API：补卡审批记录与加班入账、撤销和余额回退',
    () async {
      final employee = Api(
        base: Platform.environment['OA_TEST_BASE'] ?? 'http://127.0.0.1:8080',
      );
      final manager = Api(base: employee.base);
      num comp(dynamic rows, int year) => (rows as List)
          .where((r) => r['type_name'] == '调休' && r['year'] == year)
          .fold<num>(0, (sum, r) => sum + (r['amount'] as num));
      try {
        await employee.login(login!, password!, '二期 Dart 验收', 'test');
        await manager.login(managerLogin!, password, '二期审批验收', 'test');
        final at = shanghaiNow()
            .subtract(const Duration(hours: 2))
            .toIso8601String()
            .substring(0, 19);
        final correction = await employee.call(
          '/requests',
          method: 'POST',
          data: requestPayload(
            kind: 'CORRECTION',
            reason: 'Dart 真实补卡验收',
            startAt: at,
            punchKind: 'NORMAL',
          ),
        );
        final cid = correction['id'];
        await manager.call(
          '/requests/$cid/decision',
          method: 'POST',
          data: {'approve': true, 'comment': '同意补卡'},
        );
        final punches = await employee.call('/punches') as List;
        final punch = punches.singleWhere(
          (r) => r['correction_request_id'] == cid,
        );
        expect(punch['latitude'], isNull);
        expect(punch['exit_ip'], isNull);
        expect(
          DateTime.parse(punch['punched_at']).toUtc(),
          DateTime.parse('$at+08:00').toUtc(),
        );
        await expectLater(
          employee.call('/requests/$cid/cancel', method: 'POST'),
          throwsA(isA<ApiError>()),
        );
        final day = DateTime(
          2050,
          1,
          1,
        ).add(Duration(days: DateTime.now().millisecondsSinceEpoch % 15000));
        final date = day.toIso8601String().substring(0, 10);
        final before = comp(await employee.call('/balances'), day.year);
        final policy = await employee.call('/attendance-policy');
        final overtime = await employee.call(
          '/requests',
          method: 'POST',
          data: requestPayload(
            kind: 'OVERTIME',
            reason: 'Dart 真实加班验收',
            startAt: '${date}T09:00:00',
            endAt: '${date}T13:00:00',
          ),
        );
        final oid = overtime['id'];
        await manager.call(
          '/requests/$oid/decision',
          method: 'POST',
          data: {'approve': true, 'comment': '同意加班'},
        );
        expect(
          comp(await employee.call('/balances'), day.year),
          closeTo(before + (policy['overtime_ratio'] as num) * 0.5, 0.00001),
        );
        expect(canCancelApproved(await employee.call('/requests/$oid')), true);
        await employee.call('/requests/$oid/cancel', method: 'POST');
        expect(
          comp(await employee.call('/balances'), day.year),
          closeTo(before, 0.00001),
        );
        final history = await employee.call('/balance-history') as List;
        expect(history.where((r) => r['request_id'] == oid).length, 2);
      } finally {
        for (final api in [employee, manager]) {
          if (api.token != null) await api.call('/auth/logout', method: 'POST');
          api.client.close();
        }
      }
    },
    skip: login == null || password == null || managerLogin == null
        ? '需 OA_TEST_LOGIN / OA_TEST_PASSWORD / OA_TEST_MANAGER 专用账号'
        : false,
  );
}
