import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:oa_employee/api.dart';

void main() {
  final login = Platform.environment['OA_TEST_LOGIN'];
  final password = Platform.environment['OA_TEST_PASSWORD'];
  test(
    '真实后端：登录、消息汇总、组织通讯录、退出',
    () async {
      final api = Api(
        base: Platform.environment['OA_TEST_BASE'] ?? 'http://127.0.0.1:8080',
      );
      try {
        final user = await api.login(login!, password!, 'Dart 接口验收', 'test');
        expect(user['mustChange'], false);
        final summary = await api.call('/messages/summary');
        expect(summary['pending'], isA<num>());
        expect(summary['notifications'], isA<num>());
        expect(summary['announcements'], isA<num>());
        final people = await api.call('/directory');
        expect(people, isA<List>());
        for (final p in people as List) {
          expect(p.containsKey('phone'), false);
          expect(p.containsKey('password_hash'), false);
        }
        await api.call('/auth/logout', method: 'POST');
      } finally {
        api.client.close();
      }
    },
    skip: login == null || password == null
        ? '通过环境变量 OA_TEST_LOGIN / OA_TEST_PASSWORD 提供专用测试账号'
        : false,
  );
}
