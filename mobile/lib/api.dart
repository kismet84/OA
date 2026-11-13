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
  void Function()? onUnauthorized;
  Api({this.base = 'http://127.0.0.1:8080', http.Client? client})
    : client = client ?? http.Client();

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

  Future<dynamic> call(String path, {String method = 'GET', Json? data}) async {
    final request = http.Request(method, uri(path))..headers.addAll(headers);
    if (data != null) {
      request.headers['Content-Type'] = 'application/json';
      request.body = jsonEncode(data);
    }
    try {
      final response = await http.Response.fromStream(
        await client.send(request).timeout(const Duration(seconds: 30)),
      );
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
      final request = http.MultipartRequest('POST', uri('/attachments'))
        ..headers.addAll(headers)
        ..files.add(
          http.MultipartFile.fromBytes('file', bytes, filename: name),
        );
      final response = await http.Response.fromStream(
        await client.send(request).timeout(const Duration(seconds: 60)),
      );
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

  Future<Uint8List> documentBytes(String path) async {
    try {
      return decode(
        await client
            .get(uri(path), headers: headers)
            .timeout(const Duration(seconds: 90)),
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
        await client
            .get(uri('/attachments/$id'), headers: headers)
            .timeout(const Duration(seconds: 60)),
        binary: true,
      );
    } on ApiError {
      rethrow;
    } catch (_) {
      throw ApiError('附件下载失败，请重试');
    }
  }
}
