import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';

import 'api.dart';

const _timeoutMessage = '定位超时，请到信号较好的地方重试';
const _liveTimeout = Duration(seconds: 45);
const _freshLastKnownAge = Duration(minutes: 30);
const _staleLastKnownAge = Duration(hours: 24);
const _liveAccuracy = LocationAccuracy.high;

StreamSubscription<Position>? _warmupSub;
Position? _warmedPosition;
int _warmupGeneration = 0;

AndroidSettings _locationSettings({
  Duration? timeLimit,
  bool forceLocationManager = false,
}) => AndroidSettings(
  accuracy: _liveAccuracy,
  distanceFilter: 0,
  intervalDuration: const Duration(seconds: 1),
  maxUpdateAge: Duration.zero,
  timeLimit: timeLimit,
  forceLocationManager: forceLocationManager,
);

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
          Geolocator.getPositionStream(locationSettings: _locationSettings())
              .listen(
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

String _channelName({required bool forceLocationManager}) =>
    forceLocationManager ? '系统' : '首选';

void _logPunchLocation({
  required String channel,
  required Stopwatch watch,
  Position? position,
  required String result,
}) {
  final accuracy = position == null ? '-' : '${position.accuracy}米';
  final age = position == null
      ? '-'
      : '${DateTime.now().difference(position.timestamp).inSeconds}秒';
  debugPrint(
    '打卡定位 通道=$channel 耗时=${watch.elapsedMilliseconds}毫秒 精度=$accuracy 时效=$age 结果=$result',
  );
}

Future<Position> _livePosition(LocationSettings settings) async {
  final limit = settings.timeLimit ?? _liveTimeout;
  return Geolocator.getCurrentPosition(locationSettings: settings)
      .timeout(limit);
}

Future<Position?> _tryLiveChannel({required bool forceLocationManager}) async {
  final channel = _channelName(forceLocationManager: forceLocationManager);
  final watch = Stopwatch()..start();
  try {
    final position = await _livePosition(
      _locationSettings(
        timeLimit: _liveTimeout,
        forceLocationManager: forceLocationManager,
      ),
    );
    if (_isUsable(position, maxAge: _freshLastKnownAge)) {
      _logPunchLocation(
        channel: channel,
        watch: watch,
        position: position,
        result: '成功',
      );
      return position;
    }
    _logPunchLocation(
      channel: channel,
      watch: watch,
      position: position,
      result: '不可用',
    );
    return null;
  } catch (error) {
    _logPunchLocation(
      channel: channel,
      watch: watch,
      result: _isTimeout(error) ? '超时' : '错误:$error',
    );
    if (_isTimeout(error)) return null;
    rethrow;
  }
}

Future<Position?> _raceLivePositions() async {
  final completer = Completer<Position?>();
  var pending = 2;
  Object? nonTimeoutError;

  void finish({Position? position, Object? error}) {
    if (position != null) {
      if (!completer.isCompleted) completer.complete(position);
      return;
    }
    if (error != null) nonTimeoutError ??= error;
    pending--;
    if (pending > 0 || completer.isCompleted) return;
    if (nonTimeoutError != null) {
      completer.completeError(nonTimeoutError!);
    } else {
      completer.complete(null);
    }
  }

  Future<void> run({required bool forceLocationManager}) async {
    try {
      final position = await _tryLiveChannel(
        forceLocationManager: forceLocationManager,
      );
      finish(position: position);
    } catch (error) {
      finish(error: error);
    }
  }

  run(forceLocationManager: false);
  run(forceLocationManager: true);
  return completer.future;
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

    try {
      final position = await _raceLivePositions();
      if (position != null) {
        return (
          position: position,
          usedLastKnown: false,
          locatedAt: position.timestamp.toUtc(),
          locationSource: 'LIVE',
        );
      }
    } catch (error) {
      if (!_isTimeout(error)) rethrow;
    }
    var warmed = _readWarmupLocation();
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
