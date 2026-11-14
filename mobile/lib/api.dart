import 'dart:async';
import 'dart:convert';

import 'attachment_compress.dart';

import 'dart:typed_data';

import 'package:http/http.dart' as http;
import 'package:flutter/foundation.dart' show ValueNotifier;

final attachmentProgress = ValueNotifier<String>('');

typedef Json = Map<String, dynamic>;

class ApiError implements Exception {
  final String message;
  final int status;
  ApiError(this.message, [this.status = 0]);
  @override
  String toString() => message;
}

class Api {
  String base;
  String? token;
  final http.Client client;
  final Duration requestTimeout;
  final Duration uploadTimeout;
  final Duration documentTimeout;
  final Duration downloadTimeout;
  void Function()? onUnauthorized;
  Api({
    this.base = 'http://127.0.0.1:8080',
    http.Client? client,
    this.requestTimeout = const Duration(seconds: 30),
    this.uploadTimeout = const Duration(seconds: 60),
    this.documentTimeout = const Duration(seconds: 90),
    this.downloadTimeout = const Duration(seconds: 60),
  }) : client = client ?? http.Client();

  static String normalizeBase(String input) {
    final uri = Uri.tryParse(input.trim());
    if (uri == null ||
        !['http', 'https'].contains(uri.scheme) ||
        uri.host.isEmpty ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        (uri.path != '' &&
            uri.path != '/' &&
            uri.path != '/api' &&
            uri.path != '/api/')) {
      throw ApiError('请输入有效的 http 或 https 服务地址，不含账号、查询参数或业务路径');
    }
    return uri.replace(path: '').toString().replaceAll(RegExp(r'/$'), '');
  }

  Uri uri(String path) => Uri.parse('$base/api$path');
  Map<String, String> get headers => {
    if (token != null) 'Authorization': 'Bearer $token',
  };
  dynamic decode(http.Response response, {bool binary = false}) {
    if (response.statusCode < 200 || response.statusCode >= 300) {
      String message = '服务请求失败（${response.statusCode}）';
      try {
        message =
            jsonDecode(utf8.decode(response.bodyBytes))['message'] ?? message;
      } catch (_) {}
      if (response.statusCode == 401 && token != null) {
        token = null;
        onUnauthorized?.call();
      }
      throw ApiError(message, response.statusCode);
    }
    if (binary) return response.bodyBytes;
    return response.bodyBytes.isEmpty
        ? null
        : jsonDecode(utf8.decode(response.bodyBytes));
  }

  // One deadline includes headers and the complete body. Abortable requests stop
  // IOClient/BrowserClient even when no headers have arrived yet; cancel also
  // releases a response stream supplied by an injected client.
  Future<http.Response> _response(
    http.BaseRequest request,
    Completer<void> abort,
    Duration limit,
  ) async {
    StreamSubscription<List<int>>? subscription;
    final body = Completer<http.Response>();
    Future<http.Response> read() async {
      final response = await client.send(request);
      if (abort.isCompleted) {
        await response.stream.listen((_) {}).cancel();
        throw TimeoutException('request deadline');
      }
      final bytes = <int>[];
      subscription = response.stream.listen(
        bytes.addAll,
        onError: (Object error, StackTrace stack) {
          if (!body.isCompleted) {
            body.completeError(error, stack);
          }
        },
        onDone: () {
          if (!body.isCompleted) {
            body.complete(
              http.Response.bytes(
                bytes,
                response.statusCode,
                headers: response.headers,
                request: request,
                reasonPhrase: response.reasonPhrase,
              ),
            );
          }
        },
      );
      return body.future;
    }

    try {
      return await read().timeout(limit);
    } on TimeoutException {
      if (!abort.isCompleted) abort.complete();
      // Do not wait for a stalled peer's cancellation acknowledgement.
      unawaited(subscription?.cancel());
      throw ApiError(
        request.method == 'GET'
            ? '请求超时，请检查网络后重试'
            : '请求超时，操作结果尚未确认，请先查询单据再决定是否重试',
      );
    }
  }

  Future<dynamic> call(String path, {String method = 'GET', Json? data}) async {
    final abort = Completer<void>();
    final request = http.AbortableRequest(
      method,
      uri(path),
      abortTrigger: abort.future,
    )..headers.addAll(headers);
    if (data != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(data);
    }
    try {
      final response = await _response(request, abort, requestTimeout);
      return decode(response);
    } on ApiError {
      rethrow;
    } catch (_) {
      throw ApiError('无法连接服务，请检查网络和后端地址');
    }
  }

  Future<Json> login(
    String login,
    String password,
    String deviceName,
    String platform,
  ) async {
    if (login.trim().isEmpty || password.isEmpty) {
      throw ApiError('请填写工号或手机号及密码');
    }
    final result = await call(
      '/auth/login',
      method: 'POST',
      data: {
        'login': login.trim(),
        'password': password,
        'deviceName': deviceName,
        'platform': platform,
      },
    ) as Json;
    token = result['token'];
    return result['user'] as Json;
  }

  Future<String> upload(
    String name,
    Uint8List bytes, {
    int index = 1,
    int total = 1,
  }) async {
    attachmentProgress.value = '正在压缩 第 $index/$total 份';
    try {
      final prepared = await compressAttachment(name, bytes);
      name = prepared.name;
      bytes = prepared.bytes;
      if (bytes.isEmpty || bytes.length > 10 * 1024 * 1024) {
        throw ApiError('每份附件须为 1 字节至 10MB');
      }
      if (![
        'pdf',
        'png',
        'jpg',
        'jpeg',
      ].contains(name.split('.').last.toLowerCase())) {
        throw ApiError('附件仅支持 PDF、PNG、JPEG');
      }
      attachmentProgress.value = '正在上传 第 $index/$total 份';
      final abort = Completer<void>();
      final request =
          http.AbortableMultipartRequest(
              'POST',
              uri('/attachments'),
              abortTrigger: abort.future,
            )
            ..headers.addAll(headers)
            ..files.add(
              http.MultipartFile.fromBytes('file', bytes, filename: name),
            );
      final response = await _response(request, abort, uploadTimeout);
      return decode(response)['id'] as String;
    } on ApiError {
      rethrow;
    } on FormatException catch (e) {
      throw ApiError(e.message);
    } catch (_) {
      throw ApiError('上传失败，请检查网络后重试');
    } finally {
      attachmentProgress.value = '';
    }
  }

  Future<http.Response> _downloadResponse(String path, Duration limit) {
    final abort = Completer<void>();
    final request = http.AbortableRequest(
      'GET',
      uri(path),
      abortTrigger: abort.future,
    )..headers.addAll(headers);
    return _response(request, abort, limit);
  }

  Future<Uint8List> documentBytes(String path) async {
    try {
      return decode(
        await _downloadResponse(path, documentTimeout),
        binary: true,
      );
    } on ApiError {
      rethrow;
    } catch (_) {
      throw ApiError('文档读取失败，请重试');
    }
  }

  Future<Uint8List> download(String id) async {
    try {
      return decode(
        await _downloadResponse('/attachments/$id', downloadTimeout),
        binary: true,
      );
    } on ApiError {
      rethrow;
    } catch (_) {
      throw ApiError('附件下载失败，请重试');
    }
  }
}
