import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:geolocator/geolocator.dart';

import 'api.dart';

const _timeoutMessage = '定位超时，请到信号较好的地方重试';

bool _isTimeout(Object error) =>
    error is TimeoutException ||
    (error is PlatformException &&
        const {
          'TIMEOUT',
          'LOCATION_TIMEOUT',
          'TIMEOUT_ERROR',
        }.contains(error.code.toUpperCase()));

Future<({Position position, bool usedLastKnown})> getPunchLocation() async {
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
      final position = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.medium,
          timeLimit: Duration(seconds: 25),
        ),
      ).timeout(const Duration(seconds: 25));
      return (position: position, usedLastKnown: false);
    } catch (error) {
      if (!_isTimeout(error)) rethrow;
    }

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
      throw ApiError(_timeoutMessage);
    }
    if (cached != null) {
      final age = DateTime.now().difference(cached.timestamp);
      if (!age.isNegative &&
          age <= const Duration(minutes: 5) &&
          cached.accuracy.isFinite &&
          cached.accuracy > 0 &&
          cached.accuracy <= 200 &&
          cached.latitude.isFinite &&
          cached.latitude.abs() <= 90 &&
          cached.longitude.isFinite &&
          cached.longitude.abs() <= 180) {
        return (position: cached, usedLastKnown: true);
      }
    }
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
