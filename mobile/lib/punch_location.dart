import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';

import 'api.dart';

const _timeoutMessage = '定位超时，请到信号较好的地方重试';
const _liveTimeout = Duration(seconds: 45);
const _locationManagerTimeout = Duration(seconds: 20);
const _freshLastKnownAge = Duration(minutes: 30);
const _staleLastKnownAge = Duration(hours: 24);

StreamSubscription<Position>? _warmupSub;
Position? _warmedPosition;
int _warmupGeneration = 0;

bool _isTimeout(Object error) =>
    error is TimeoutException ||
    (error is PlatformException &&
        const {
          'TIMEOUT',
          'LOCATION_TIMEOUT',
          'TIMEOUT_ERROR',
        }.contains(error.code.toUpperCase()));

bool _isUsable(Position position, {required Duration maxAge}) {
  final age = DateTime.now().difference(position.timestamp);
  return !age.isNegative &&
      age <= maxAge &&
      position.accuracy.isFinite &&
      position.accuracy > 0 &&
      position.accuracy <= 200 &&
      position.latitude.isFinite &&
      position.latitude.abs() <= 90 &&
      position.longitude.isFinite &&
      position.longitude.abs() <= 180;
}

void startPunchLocationWarmup() {
  final generation = ++_warmupGeneration;
  _warmupSub?.cancel();
  _warmupSub = null;
  Future<void>(() async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) return;
      final permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        return;
      }
      if (generation != _warmupGeneration) return;
      _warmupSub =
          Geolocator.getPositionStream(
            locationSettings: const LocationSettings(
              accuracy: LocationAccuracy.medium,
              distanceFilter: 0,
            ),
          ).listen(
            (position) {
              if (_isUsable(position, maxAge: _freshLastKnownAge)) {
                _warmedPosition = position;
              }
            },
            onError: (Object error) {
              debugPrint('预热定位失败：$error');
            },
          );
    } catch (error) {
      debugPrint('启动定位预热失败：$error');
    }
  });
}

void stopPunchLocationWarmup() {
  _warmupGeneration++;
  _warmupSub?.cancel();
  _warmupSub = null;
}

@visibleForTesting
void resetPunchLocationWarmupForTest() {
  stopPunchLocationWarmup();
  _warmedPosition = null;
}

({
  Position position,
  bool usedLastKnown,
  DateTime locatedAt,
  String locationSource,
})?
_readWarmupLocation() {
  final warmed = _warmedPosition;
  if (warmed != null && _isUsable(warmed, maxAge: _freshLastKnownAge)) {
    return (
      position: warmed,
      usedLastKnown: false,
      locatedAt: warmed.timestamp.toUtc(),
      locationSource: 'WARMUP_CACHE',
    );
  }
  return null;
}

Future<Position> _livePosition(LocationSettings settings) async {
  final limit = settings.timeLimit ?? _liveTimeout;
  return Geolocator.getCurrentPosition(locationSettings: settings)
      .timeout(limit);
}

Future<
  ({
    Position position,
    bool usedLastKnown,
    DateTime locatedAt,
    String locationSource,
  })
>
getPunchLocation({bool allowStaleLastKnown = true}) async {
  try {
    if (!await Geolocator.isLocationServiceEnabled()) {
      throw ApiError('定位服务未开启，请在系统设置中开启');
    }
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.denied) {
      throw ApiError('定位权限被拒绝；允许定位后才能打卡');
    }
    if (permission == LocationPermission.deniedForever) {
      throw ApiError('定位权限已被系统禁止，请打开应用设置允许定位');
    }

    var warmed = _readWarmupLocation();
    if (warmed != null) return warmed;

    try {
      final position = await _livePosition(
        const LocationSettings(
          accuracy: LocationAccuracy.medium,
          timeLimit: _liveTimeout,
        ),
      );
      return (
        position: position,
        usedLastKnown: false,
        locatedAt: position.timestamp.toUtc(),
        locationSource: 'LIVE',
      );
    } catch (error) {
      if (!_isTimeout(error)) rethrow;
    }
    warmed = _readWarmupLocation();
    if (warmed != null) return warmed;

    try {
      final position = await _livePosition(
        AndroidSettings(
          accuracy: LocationAccuracy.medium,
          timeLimit: _locationManagerTimeout,
          forceLocationManager: true,
        ),
      );
      return (
        position: position,
        usedLastKnown: false,
        locatedAt: position.timestamp.toUtc(),
        locationSource: 'LIVE',
      );
    } catch (error) {
      if (!_isTimeout(error)) rethrow;
    }
    warmed = _readWarmupLocation();
    if (warmed != null) return warmed;

    Position? cached;
    try {
      cached = await Geolocator.getLastKnownPosition().timeout(
        const Duration(seconds: 3),
      );
    } on LocationServiceDisabledException {
      rethrow;
    } on PermissionDeniedException {
      rethrow;
    } catch (error) {
      debugPrint('读取最近定位失败：$error');
    }
    if (cached != null &&
        (_isUsable(cached, maxAge: _freshLastKnownAge) ||
            (allowStaleLastKnown &&
                _isUsable(cached, maxAge: _staleLastKnownAge)))) {
      return (
        position: cached,
        usedLastKnown: true,
        locatedAt: cached.timestamp.toUtc(),
        locationSource: _isUsable(cached, maxAge: _freshLastKnownAge)
            ? 'LAST_KNOWN'
            : 'STALE_FALLBACK',
      );
    }
    warmed = _readWarmupLocation();
    if (warmed != null) return warmed;
    throw ApiError(_timeoutMessage);
  } on ApiError {
    rethrow;
  } on LocationServiceDisabledException {
    throw ApiError('定位服务未开启，请在系统设置中开启');
  } on PermissionDeniedException {
    throw ApiError('定位权限被拒绝；允许定位后才能打卡');
  } catch (error) {
    debugPrint('获取打卡定位失败：$error');
    throw ApiError('获取定位失败，请检查系统定位设置后重试');
  }
}
