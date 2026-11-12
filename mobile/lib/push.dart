import 'dart:async';
import 'dart:io';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';

abstract class PushDevice {
  Future<void> initialize();
  Future<bool> permission();
  Future<String?> token();
  Stream<String> get refreshed;
  Stream<void> get foreground;
  Stream<void> get opened;
  Future<bool> initialOpen();
}

/// Android only: no Firebase initialization or APNs calls on other platforms.
class AndroidPushDevice implements PushDevice {
  FirebaseMessaging get messaging => FirebaseMessaging.instance;
  @override
  Future<void> initialize() => Firebase.initializeApp();
  @override
  Future<bool> permission() async {
    final prefs = await SharedPreferences.getInstance();
    var settings = await messaging.getNotificationSettings();
    if (prefs.getBool('oa_push_permission_asked') != true) {
      await prefs.setBool('oa_push_permission_asked', true);
      settings = await messaging.requestPermission();
    }
    return settings.authorizationStatus == AuthorizationStatus.authorized;
  }

  @override
  Future<String?> token() => messaging.getToken();
  @override
  Stream<String> get refreshed => messaging.onTokenRefresh;
  @override
  Stream<void> get foreground => FirebaseMessaging.onMessage.map((_) {});
  @override
  Stream<void> get opened => FirebaseMessaging.onMessageOpenedApp.map((_) {});
  @override
  Future<bool> initialOpen() async =>
      await messaging.getInitialMessage() != null;
}

class PushRegistration {
  final Api api;
  final PushDevice device;
  final void Function() onForeground;
  final void Function() onOpen;
  final List<StreamSubscription<dynamic>> _subscriptions = [];
  bool _ready = false;
  bool _active = false;
  String? _token;
  // Serialize registration and logout so a late token cannot re-register after logout.
  Future<void> _pending = Future.value();
  PushRegistration(
    this.api,
    this.device, {
    required this.onForeground,
    required this.onOpen,
  });

  static PushRegistration? android(
    Api api, {
    required void Function() onForeground,
    required void Function() onOpen,
  }) => Platform.isAndroid
      ? PushRegistration(
          api,
          AndroidPushDevice(),
          onForeground: onForeground,
          onOpen: onOpen,
        )
      : null;

  Future<void> start() async {
    _active = true;
    try {
      if (!_ready) {
        await device.initialize();
        _subscriptions.add(
          device.refreshed.listen((value) {
            _register(value);
          }),
        );
        _subscriptions.add(
          device.foreground.listen((_) {
            if (_active) onForeground();
          }),
        );
        _subscriptions.add(
          device.opened.listen((_) {
            if (_active) onOpen();
          }),
        );
        _ready = true;
        if (await device.initialOpen() && _active) onOpen();
      }
      if (await device.permission()) {
        final value = await device.token();
        if (value != null) await _register(value);
      }
    } catch (_) {
      // Permission/Firebase/network failures must never block the employee workflow.
    }
  }

  Future<void> _register(String value) {
    _pending = _pending.then((_) async {
      try {
        if (!_active || api.token == null || !await device.permission()) return;
        if (!_active) return;
        await api.call(
          '/push/tokens',
          method: 'POST',
          data: {'token': value, 'platform': 'ANDROID', 'deviceName': '安卓员工端'},
        );
        final old = _token;
        _token = value;
        if (old != null && old != value) {
          await api.call(
            '/push/tokens',
            method: 'DELETE',
            data: {'token': old},
          );
        }
      } catch (_) {}
    });
    return _pending;
  }

  void deactivate() {
    _active = false;
  }

  Future<void> logout() async {
    _active = false;
    await _pending;
    try {
      if (_token != null) {
        await api.call(
          '/push/tokens',
          method: 'DELETE',
          data: {'token': _token},
        );
      }
    } catch (_) {
      // /auth/logout also removes registrations associated with the current session.
    }
    _token = null;
  }

  void dispose() {
    _active = false;
    for (final subscription in _subscriptions) {
      subscription.cancel();
    }
  }
}
