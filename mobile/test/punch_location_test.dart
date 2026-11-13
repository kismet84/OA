import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:network_info_plus_platform_interface/network_info_plus_platform_interface.dart';
import 'package:oa_employee/api.dart';
import 'package:oa_employee/pages.dart';
import 'package:oa_employee/punch_location.dart';

Position position({
  Duration age = const Duration(minutes: 1),
  double accuracy = 20,
  double latitude = 31.23,
}) => Position(
  latitude: latitude,
  longitude: 121.47,
  timestamp: DateTime.now().subtract(age),
  accuracy: accuracy,
  altitude: 0,
  altitudeAccuracy: 0,
  heading: 0,
  headingAccuracy: 0,
  speed: 0,
  speedAccuracy: 0,
);

class FakeLocation extends GeolocatorPlatform {
  bool enabled = true;
  LocationPermission permission = LocationPermission.whileInUse;
  Object? error;
  Object? locationManagerError;
  Position? current;
  Position? locationManagerCurrent;
  Position? cached;
  int cacheCalls = 0;
  final warmupStarted = Completer<void>();
  Future<void> Function(bool forced)? beforeCurrentResult;
  Future<void> Function()? beforeCacheResult;
  final currentSettings = <LocationSettings?>[];
  final warmup = StreamController<Position>.broadcast();
  @override
  Future<bool> isLocationServiceEnabled() async => enabled;
  @override
  Future<LocationPermission> checkPermission() async => permission;
  @override
  Future<LocationPermission> requestPermission() async => permission;
  @override
  Stream<Position> getPositionStream({LocationSettings? locationSettings}) {
    warmupStarted.complete();
    return warmup.stream;
  }

  @override
  Future<Position> getCurrentPosition({
    LocationSettings? locationSettings,
  }) async {
    currentSettings.add(locationSettings);
    expect(locationSettings?.accuracy, LocationAccuracy.medium);
    final forced =
        locationSettings is AndroidSettings &&
        locationSettings.forceLocationManager;
    await beforeCurrentResult?.call(forced);
    if (forced) {
      expect(locationSettings.timeLimit, const Duration(seconds: 20));
      if (locationManagerError != null) throw locationManagerError!;
      return locationManagerCurrent ?? current!;
    }
    expect(locationSettings?.timeLimit, const Duration(seconds: 45));
    if (error != null) throw error!;
    return current!;
  }

  @override
  Future<Position?> getLastKnownPosition({
    bool forceLocationManager = false,
  }) async {
    cacheCalls++;
    await beforeCacheResult?.call();
    return cached;
  }
}

class UnavailableWifi extends NetworkInfoPlatform {
  int calls = 0;
  @override
  Future<String?> getWifiName() async {
    calls++;
    throw PlatformException(code: 'SSID_UNAVAILABLE');
  }
}

void main() {
  final original = GeolocatorPlatform.instance;
  final originalWifi = NetworkInfoPlatform.instance;
  late FakeLocation location;
  late UnavailableWifi wifi;
  setUp(() {
    resetPunchLocationWarmupForTest();
    location = FakeLocation();
    GeolocatorPlatform.instance = location;
    wifi = UnavailableWifi();
    NetworkInfoPlatform.instance = wifi;
  });
  tearDown(() {
    resetPunchLocationWarmupForTest();
    GeolocatorPlatform.instance = original;
    NetworkInfoPlatform.instance = originalWifi;
  });

  test('实时定位成功，不读取缓存', () async {
    location.current = position();
    final result = await getPunchLocation();
    expect(result.position, location.current);
    expect(result.usedLastKnown, false);
    expect(result.locationSource, 'LIVE');
    expect(result.locatedAt, result.position.timestamp.toUtc());
    expect(location.cacheCalls, 0);
    expect(location.currentSettings, hasLength(1));
  });

  test('融合定位超时后回退到系统定位服务', () async {
    location.error = TimeoutException('test');
    location.locationManagerCurrent = position(latitude: 30.5);
    final result = await getPunchLocation();
    expect(result.position, location.locationManagerCurrent);
    expect(result.usedLastKnown, false);
    expect(result.locationSource, 'LIVE');
    expect(result.locatedAt, result.position.timestamp.toUtc());
    expect(location.cacheCalls, 0);
    expect(location.currentSettings, hasLength(2));
    expect(location.currentSettings.last, isA<AndroidSettings>());
  });

  test('Dart 和平台定位超时均可回退到 30 分钟内且精度有效的定位', () async {
    for (final error in [
      TimeoutException('test'),
      PlatformException(code: 'TIMEOUT'),
    ]) {
      location.error = error;
      location.locationManagerError = error;
      location.cached = position(
        accuracy: 200,
        age: const Duration(minutes: 29),
      );
      final result = await getPunchLocation(allowStaleLastKnown: false);
      expect(result.position, location.cached);
      expect(result.usedLastKnown, true);
    }
  });

  test('正常打卡允许使用 24 小时内的最近定位；出勤则仍超时', () async {
    location.error = TimeoutException('test');
    location.locationManagerError = TimeoutException('test');
    location.cached = position(age: const Duration(hours: 13, minutes: 30));
    final normal = await getPunchLocation();
    expect(normal.position, location.cached);
    expect(normal.usedLastKnown, true);
    expect(normal.locationSource, 'STALE_FALLBACK');
    await expectLater(
      getPunchLocation(allowStaleLastKnown: false),
      throwsA(
        isA<ApiError>().having((e) => e.message, 'message', '定位超时，请到信号较好的地方重试'),
      ),
    );
  });

  test('无缓存、超过一天、未来时间、无效精度及坐标均显示明确超时文案', () async {
    location.error = TimeoutException('test');
    location.locationManagerError = TimeoutException('test');
    for (final cached in [
      null,
      position(age: const Duration(hours: 24, minutes: 1)),
      position(age: const Duration(minutes: -1)),
      position(accuracy: 201),
      position(accuracy: 0),
      position(accuracy: double.nan),
      position(latitude: 100),
    ]) {
      location.cached = cached;
      await expectLater(
        getPunchLocation(),
        throwsA(
          isA<ApiError>().having(
            (e) => e.message,
            'message',
            '定位超时，请到信号较好的地方重试',
          ),
        ),
      );
    }
  });

  test('定位服务及权限提示保持明确；非超时错误不使用缓存', () async {
    location.enabled = false;
    await expectLater(
      getPunchLocation(),
      throwsA(
        isA<ApiError>().having(
          (e) => e.message,
          'message',
          '定位服务未开启，请在系统设置中开启',
        ),
      ),
    );
    location.enabled = true;
    for (final permission in [
      LocationPermission.denied,
      LocationPermission.deniedForever,
    ]) {
      location.permission = permission;
      await expectLater(
        getPunchLocation(),
        throwsA(
          isA<ApiError>().having(
            (e) => e.message,
            'message',
            permission == LocationPermission.denied
                ? '定位权限被拒绝；允许定位后才能打卡'
                : '定位权限已被系统禁止，请打开应用设置允许定位',
          ),
        ),
      );
    }
    location.permission = LocationPermission.whileInUse;
    location.error = PlatformException(code: 'LOCATION_UPDATE_FAILURE');
    location.cached = position();
    await expectLater(
      getPunchLocation(),
      throwsA(
        isA<ApiError>().having(
          (e) => e.message,
          'message',
          '获取定位失败，请检查系统定位设置后重试',
        ),
      ),
    );
    expect(location.cacheCalls, 0);
  });

  test('预热缓存保留采集时刻并明确来源', () async {
    startPunchLocationWarmup();
    await Future<void>.delayed(const Duration(milliseconds: 10));
    final cached = position(age: const Duration(minutes: 20));
    location.warmup.add(cached);
    await Future<void>.delayed(Duration.zero);
    final result = await getPunchLocation();
    expect(result.locationSource, 'WARMUP_CACHE');
    expect(result.locatedAt, cached.timestamp.toUtc());
    expect(location.currentSettings, isEmpty);
  });

  for (final stage in ['首轮超时', '两轮超时', '系统缓存为空', '系统缓存读取失败']) {
    test('点击后预热成功且实时定位超时则使用预热缓存：$stage', () async {
      startPunchLocationWarmup();
      await location.warmupStarted.future;
      final warmed = position(age: const Duration(minutes: 20));
      Future<void> publishWarmup() async {
        location.warmup.add(warmed);
        await Future<void>.delayed(Duration.zero);
      }

      location.error = TimeoutException('test');
      location.locationManagerError = TimeoutException('test');
      location.beforeCurrentResult = (forced) async {
        if ((stage == '首轮超时' && !forced) || (stage == '两轮超时' && forced)) {
          await publishWarmup();
        }
      };
      location.beforeCacheResult = () async {
        await publishWarmup();
        if (stage == '系统缓存读取失败') throw TimeoutException('cache');
      };
      final result = await getPunchLocation();
      expect(result.position, warmed);
      expect(result.locationSource, 'WARMUP_CACHE');
      expect(result.usedLastKnown, false);
      expect(result.locatedAt, warmed.timestamp.toUtc());
      expect(location.cached, isNull);
      expect(location.currentSettings, hasLength(stage == '首轮超时' ? 1 : 2));
      expect(location.cacheCalls, stage.startsWith('系统缓存') ? 1 : 0);
    });
  }

  test('等待实时定位期间预热成功仍优先返回实时结果', () async {
    startPunchLocationWarmup();
    await location.warmupStarted.future;
    location.current = position(latitude: 30.5);
    location.beforeCurrentResult = (_) async {
      location.warmup.add(position());
      await Future<void>.delayed(Duration.zero);
    };
    final result = await getPunchLocation();
    expect(result.position, location.current);
    expect(result.locationSource, 'LIVE');
    expect(location.cacheCalls, 0);
  });

  testWidgets('超时不提交；再次定位使用缓存时显示来源和坐标，SSID失败仍可提交', (tester) async {
    location.error = TimeoutException('test');
    location.locationManagerError = TimeoutException('test');
    Json? submitted;
    final api = Api(
      client: MockClient((request) async {
        if (request.method == 'POST') {
          submitted = jsonDecode(request.body) as Json;
        }
        return http.Response(request.method == 'POST' ? '{"id":1}' : '[]', 200);
      }),
    );
    await tester.pumpWidget(MaterialApp(home: PunchPage(api: api)));
    await tester.pumpAndSettle();
    await tester.tap(find.text('获取定位并打卡'));
    await tester.pumpAndSettle();
    expect(find.text('定位超时，请到信号较好的地方重试'), findsOneWidget);
    expect(submitted, isNull);
    location.cached = position();
    await tester.tap(find.text('获取定位并打卡'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.textContaining('使用最近一次定位'), findsWidgets);
    expect(submitted, isNull);
    await tester.tap(find.text('确认打卡'));
    await tester.pumpAndSettle();
    expect(find.textContaining('纬度 31.230000，经度 121.470000'), findsOneWidget);
    expect(find.textContaining('精度 20.0 米'), findsOneWidget);
    expect(submitted?['locationSource'], 'LAST_KNOWN');
    expect(
      submitted?['locatedAt'],
      location.cached!.timestamp.toUtc().toIso8601String(),
    );
    expect(submitted?['latitude'], 31.23);
    expect(submitted?['accuracy'], 20);
    expect(submitted?.containsKey('ssid'), false);
    expect(wifi.calls, 1);
    expect(tester.takeException(), isNull);
  });
}
