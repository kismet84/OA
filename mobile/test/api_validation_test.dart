import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/validation.dart';

void main() {
  test('登录上报设备并在后续请求携带 token；401清理会话', () async {
    var expired = false;
    var n = 0;
    final api = Api(
      client: MockClient((r) async {
        n++;
        if (n == 1) {
          expect(r.url.path, '/api/auth/login');
          expect(jsonDecode(r.body), {
            'login': 'E001',
            'password': 'Secret@2026',
            'deviceName': '测试手机',
            'platform': 'Android',
          });
          return http.Response(
            '{"token":"secret","user":{"id":7,"mustChange":false}}',
            200,
          );
        }
        expect(r.headers['Authorization'], 'Bearer secret');
        return http.Response('{"message":"expired"}', 401);
      }),
    )..onUnauthorized = () => expired = true;
    expect(
      (await api.login(' E001 ', 'Secret@2026', '测试手机', 'Android'))['id'],
      7,
    );
    await expectLater(api.call('/messages/summary'), throwsA(isA<ApiError>()));
    expect(api.token, isNull);
    expect(expired, isTrue);
  });
  test('地址允许公网HTTPS，拒绝嵌入凭据和业务路径', () {
    expect(
      Api.normalizeBase('https://oa.example.com/api/'),
      'https://oa.example.com',
    );
    for (final v in [
      'file:///tmp/a',
      'https://user:pass@oa.example.com',
      'http://host/requests',
      'http://host?token=x',
    ]) {
      expect(() => Api.normalizeBase(v), throwsA(isA<ApiError>()));
    }
  });
  test('打卡200米通过；201米、旧来源、缺字段与过期均拒绝', () {
    final now = DateTime.now();
    final valid = punchPayload(
      'NORMAL',
      31,
      120,
      200,
      locationSource: 'LIVE',
      locatedAt: now,
    );
    for (final latitude in [null, double.nan, 91.0]) {
      expect(
        () => punchPayload(
          'NORMAL',
          latitude,
          120,
          12,
          locationSource: 'LIVE',
          locatedAt: now,
        ),
        throwsA(isA<ApiError>()),
      );
    }
    expect(
      () => punchPayload(
        'FIELD',
        31,
        120,
        0,
        locationSource: 'LIVE',
        locatedAt: now,
      ),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => punchPayload(
        'FIELD',
        31,
        120,
        12,
        locationSource: 'STALE_FALLBACK',
        locatedAt: now,
      ),
      throwsA(isA<ApiError>()),
    );
    expect(valid.containsKey('exitIp'), false);
    expect(valid['accuracy'], 200);
    expect(valid['locationSource'], 'LIVE');
    expect(valid['locatedAt'], now.toUtc().toIso8601String());
    for (final source in [
      'LIVE',
      'WARMUP_CACHE',
      'LAST_KNOWN',
      'STALE_FALLBACK',
    ]) {
      expect(
        punchPayload(
          'NORMAL',
          31,
          120,
          200,
          locationSource: source,
          locatedAt: now,
        )['locationSource'],
        source,
      );
      expect(
        () => punchPayload(
          'NORMAL',
          31,
          120,
          200,
          locationSource: source,
          locatedAt: now.subtract(const Duration(minutes: 6)),
        ),
        throwsA(isA<ApiError>()),
      );
    }
    expect(
      () => punchPayload(
        'NORMAL',
        31,
        120,
        201,
        locationSource: 'LIVE',
        locatedAt: now,
      ),
      throwsA(isA<ApiError>()),
    );
    expect(() => punchPayload('NORMAL', 31, 120, 12), throwsA(isA<ApiError>()));
    expect(
      () => punchPayload('NORMAL', 31, 120, 12, locationSource: 'LIVE'),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => punchPayload('NORMAL', 31, 120, 12, locatedAt: now),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => punchPayload(
        'NORMAL',
        31,
        120,
        12,
        locationSource: 'UNKNOWN',
        locatedAt: now,
      ),
      throwsA(isA<ApiError>()),
    );
    expect(
      () => punchPayload(
        'NORMAL',
        31,
        120,
        12,
        locationSource: 'LIVE',
        locatedAt: now.add(const Duration(minutes: 3)),
      ),
      throwsA(isA<ApiError>()),
    );
  });
  test('报销采用分校验合计，要求明细及多票据合法', () {
    Json payload({
      String total = '0.30',
      List<String> files = const ['a', 'b'],
    }) => requestPayload(
      kind: 'EXPENSE',
      reason: '交通',
      amount: total,
      items: [
        {'description': '地铁', 'amount': '0.10'},
        {'description': '公交', 'amount': '0.20'},
      ],
      attachmentIds: files,
      cc: [3, 3, 4],
    );
    expect(payload()['amount'], 0.3);
    expect(payload()['ccUserIds'], [3, 4]);
    expect(() => payload(total: '0.31'), throwsA(isA<ApiError>()));
    expect(() => payload(files: []), throwsA(isA<ApiError>()));
    expect(() => payload(files: ['a', 'a']), throwsA(isA<ApiError>()));
    for (final v in ['0', '-1', '0.001', 'NaN', '100000000']) {
      expect(() => cents(v), throwsA(isA<ApiError>()));
    }
  });
  test('半天、跨年、证明、驳回与改密校验', () {
    final y = shanghaiNow().year + 1;
    Json payload({String? end, String? proof, int? type = 1}) => requestPayload(
      kind: 'LEAVE',
      reason: '个人安排',
      typeId: type,
      startAt: '$y-02-01T09:00:00',
      endAt: end ?? '$y-02-01T13:00:00',
      proofRequired: true,
      attachmentId: proof,
    );
    expect(() => payload(), throwsA(isA<ApiError>()));
    expect(payload(proof: 'proof')['startAt'], '$y-02-01T09:00:00');
    expect(() => payload(proof: 'proof', type: null), throwsA(isA<ApiError>()));
    expect(
      () => payload(proof: 'proof', end: '${y + 1}-02-01T13:00:00'),
      throwsA(isA<ApiError>()),
    );
    expect(() => validateDecision(false, ' '), throwsA(isA<ApiError>()));
    validateDecision(true, '');
    expect(() => validatePassword('old', '短密码'), throwsA(isA<ApiError>()));
  });
}
