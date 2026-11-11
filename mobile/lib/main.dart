import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:device_info_plus/device_info_plus.dart';

import 'api.dart';
import 'ui.dart';
import 'validation.dart';
import 'pages.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const EmployeeApp());
}

class EmployeeApp extends StatefulWidget {
  final Api? api;
  final bool restoreSession;
  const EmployeeApp({super.key, this.api, this.restoreSession = true});
  @override
  State<EmployeeApp> createState() => _EmployeeAppState();
}

class _EmployeeAppState extends State<EmployeeApp> {
  late final Api api = widget.api ?? Api();
  final storage = const FlutterSecureStorage();
  final navigator = GlobalKey<NavigatorState>();
  Json? user;
  bool loading = true;
  String? startupError;
  @override
  void initState() {
    super.initState();
    api.onUnauthorized = expired;
    restore();
  }

  void expired() {
    if (widget.restoreSession) storage.delete(key: 'oa_token');
    navigator.currentState?.popUntil((r) => r.isFirst);
    if (mounted) setState(() => user = null);
  }

  Future<void> restore() async {
    if (widget.restoreSession) {
      try {
        final prefs = await SharedPreferences.getInstance();
        api.base = Api.normalizeBase(prefs.getString('oa_base') ?? api.base);
        api.token = await storage.read(key: 'oa_token');
        if (api.token != null) user = (await api.call('/auth/me'))['user'];
      } catch (e) {
        startupError = '$e';
      }
    }
    if (mounted) setState(() => loading = false);
  }

  Future<void> loggedIn(Json value) async {
    if (widget.restoreSession) {
      await storage.write(key: 'oa_token', value: api.token);
    }
    if (mounted) setState(() => user = value);
  }

  Future<void> logout() async {
    try {
      await api.call('/auth/logout', method: 'POST');
    } finally {
      api.token = null;
      expired();
    }
  }

  @override
  void dispose() {
    api.onUnauthorized = null;
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
    navigatorKey: navigator,
    title: '员工办公',
    debugShowCheckedModeBanner: false,
    locale: const Locale('zh', 'CN'),
    supportedLocales: const [Locale('zh', 'CN')],
    localizationsDelegates: GlobalMaterialLocalizations.delegates,
    theme: ThemeData(
      useMaterial3: true,
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff176b64)),
      scaffoldBackgroundColor: const Color(0xfff5f7f9),
      appBarTheme: const AppBarTheme(centerTitle: false),
      cardTheme: const CardThemeData(margin: EdgeInsets.symmetric(vertical: 6)),
    ),
    home: loading
        ? const Scaffold(body: Center(child: CircularProgressIndicator()))
        : user == null
        ? LoginPage(api: api, onLogin: loggedIn, startupError: startupError)
        : user!['mustChange'] == true
        ? PasswordPage(
            api: api,
            forced: true,
            onChanged: expired,
            onLogout: logout,
          )
        : HomePage(api: api, user: user!, onLogout: logout, onExpired: expired),
  );
}

class LoginPage extends StatefulWidget {
  final Api api;
  final Future<void> Function(Json) onLogin;
  final String? startupError;
  const LoginPage({
    super.key,
    required this.api,
    required this.onLogin,
    this.startupError,
  });
  @override
  State<LoginPage> createState() => _LoginState();
}

class _LoginState extends State<LoginPage> with Guard<LoginPage> {
  final login = TextEditingController(), password = TextEditingController();
  late final address = TextEditingController(text: widget.api.base);
  final device = TextEditingController(text: '员工手机');
  @override
  void initState() {
    super.initState();
    loadDevice();
  }

  Future<void> loadDevice() async {
    try {
      final info = DeviceInfoPlugin();
      String name = '员工手机';
      if (Platform.isAndroid) {
        final d = await info.androidInfo;
        name = '${d.manufacturer} ${d.model}';
      }
      if (Platform.isIOS) {
        final d = await info.iosInfo;
        name = d.name;
      }
      if (mounted) {
        device.text = name.length > 100 ? name.substring(0, 100) : name;
      }
    } catch (_) {
      /* Device name is editable even without device-info permission. */
    }
  }

  @override
  void dispose() {
    login.dispose();
    password.dispose();
    address.dispose();
    device.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('员工办公')),
    body: SafeArea(
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const Icon(
            Icons.business_outlined,
            size: 64,
            color: Color(0xff176b64),
          ),
          section('欢迎使用员工办公'),
          const Text('使用工号或手机号登录，处理工作中的日常事务。'),
          if (widget.startupError != null) Text(widget.startupError!),
          field(login, '工号或手机号', max: 80),
          field(password, '密码', secret: true),
          ExpansionTile(
            title: const Text('服务地址与设备'),
            children: [
              field(address, '后端地址（不含 /api）', keyboard: TextInputType.url),
              field(device, '本机设备名称', max: 100),
              const Text('开发默认 127.0.0.1；安卓模拟器请改为 10.0.2.2，真机请填电脑局域网地址。'),
            ],
          ),
          feedback,
          const SizedBox(height: 16),
          FilledButton(
            onPressed: busy
                ? null
                : () => run(() async {
                    final base = Api.normalizeBase(address.text);
                    if (base != widget.api.base) widget.api.token = null;
                    widget.api.base = base;
                    final prefs = await SharedPreferences.getInstance();
                    await prefs.setString('oa_base', base);
                    final user = await widget.api.login(
                      login.text,
                      password.text,
                      device.text.trim().isEmpty ? '员工手机' : device.text.trim(),
                      Platform.isIOS ? 'iOS' : 'Android',
                    );
                    await widget.onLogin(user);
                    password.clear();
                  }),
            child: const Text('登录'),
          ),
        ],
      ),
    ),
  );
}

class PasswordPage extends StatefulWidget {
  final Api api;
  final bool forced;
  final VoidCallback onChanged;
  final Future<void> Function()? onLogout;
  const PasswordPage({
    super.key,
    required this.api,
    this.forced = false,
    required this.onChanged,
    this.onLogout,
  });
  @override
  State<PasswordPage> createState() => _PasswordState();
}

class _PasswordState extends State<PasswordPage> with Guard<PasswordPage> {
  final old = TextEditingController(),
      next = TextEditingController(),
      repeat = TextEditingController();
  @override
  void dispose() {
    old.dispose();
    next.dispose();
    repeat.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: !widget.forced,
    child: Scaffold(
      appBar: AppBar(
        title: Text(widget.forced ? '首次登录 · 修改密码' : '修改密码'),
        automaticallyImplyLeading: !widget.forced,
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('新密码至少10位，最多72个 UTF-8 字节。修改后所有设备须重新登录。'),
          field(old, '原密码', secret: true),
          field(next, '新密码', secret: true),
          field(repeat, '再次输入新密码', secret: true),
          feedback,
          FilledButton(
            onPressed: busy
                ? null
                : () => run(() async {
                    validatePassword(old.text, next.text);
                    if (next.text != repeat.text) throw ApiError('两次新密码不一致');
                    await widget.api.call(
                      '/auth/password',
                      method: 'POST',
                      data: {'oldPassword': old.text, 'newPassword': next.text},
                    );
                    widget.api.token = null;
                    widget.onChanged();
                  }),
            child: const Text('修改并重新登录'),
          ),
          if (widget.forced)
            TextButton(
              onPressed: busy
                  ? null
                  : () => run(() async {
                      await widget.onLogout?.call();
                    }),
              child: const Text('退出登录'),
            ),
        ],
      ),
    ),
  );
}
