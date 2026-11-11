import 'dart:io';

import 'package:flutter/material.dart';
import 'package:open_filex/open_filex.dart';
import 'package:path_provider/path_provider.dart';

import 'api.dart';

const labels = {
  'LEAVE': '请假',
  'TRAVEL': '出差',
  'OUTING': '外出',
  'EXPENSE': '报销',
  'PENDING': '待审批',
  'APPROVED': '已批准',
  'REJECTED': '已驳回',
  'WITHDRAWN': '已撤回',
  'CANCELLED': '已撤销',
  'UNPAID': '待支付',
  'PAID': '已支付',
  'NORMAL': '正常打卡',
  'FIELD': '出勤',
  'EMPLOYEE': '员工',
  'SUPERVISOR': '直属上级',
  'DEPT_HEAD': '部门负责人',
  'FINANCE': '财务/行政',
  'ADMIN': '管理员',
  'pending': '待我审批',
  'initiated': '我发起的',
  'cc': '抄送我的',
  'notifications': '系统通知',
  'announcements': '公告',
};
String textOf(dynamic v) => v == null || v == '' ? '未填写' : labels['$v'] ?? '$v';
String timeOf(dynamic value) {
  if (value == null) return '—';
  var text = '$value';
  if (RegExp(r'(Z|[+-]\d{2}:\d{2})$').hasMatch(text)) {
    final date = DateTime.tryParse(text);
    if (date != null) {
      text = date
          .toUtc()
          .add(const Duration(hours: 8))
          .toIso8601String()
          .replaceAll('Z', '');
    }
  }
  return text.replaceAll('T', ' ').split('.').first;
}

List<Json> records(dynamic value) =>
    (value as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();

mixin Guard<T extends StatefulWidget> on State<T> {
  bool busy = false;
  String? error;
  Future<void> run(Future<void> Function() action, {String? success}) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      await action();
      if (mounted && success != null) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(success)));
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          error = e is ApiError ? e.message : '操作未完成，请检查权限、网络后重试';
        });
      }
    } finally {
      if (mounted) {
        setState(() {
          busy = false;
        });
      }
    }
  }

  Widget get feedback => Column(
    children: [
      if (busy) const LinearProgressIndicator(),
      if (error != null)
        Padding(
          padding: const EdgeInsets.all(12),
          child: Text(
            error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        ),
    ],
  );
}

Widget field(
  TextEditingController controller,
  String label, {
  bool secret = false,
  int lines = 1,
  int? max,
  TextInputType? keyboard,
}) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 8),
  child: TextField(
    controller: controller,
    obscureText: secret,
    maxLines: lines,
    maxLength: max,
    keyboardType: keyboard,
    decoration: InputDecoration(
      labelText: label,
      border: const OutlineInputBorder(),
    ),
  ),
);
Widget section(String title) => Padding(
  padding: const EdgeInsets.only(top: 20, bottom: 8),
  child: Text(
    title,
    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
  ),
);
Widget empty(String message) => Padding(
  padding: const EdgeInsets.all(28),
  child: Center(child: Text(message)),
);
Future<void> go(BuildContext context, Widget page) =>
    Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => page));
Future<bool> confirm(BuildContext context, String title, String body) async =>
    await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('确认'),
          ),
        ],
      ),
    ) ??
    false;
Future<void> openAttachment(Api api, String id, String name) async {
  final bytes = await api.download(id);
  final dir = await getTemporaryDirectory();
  // Use an opaque ID prefix and strip path components from server-supplied names.
  final safeName = name.replaceAll(RegExp(r'[^\w\u4e00-\u9fff. -]'), '_');
  final extension = bytes.length > 4 && bytes[0] == 37
      ? 'pdf'
      : bytes.length > 8 && bytes[0] == 137
      ? 'png'
      : 'jpg';
  final filename =
      RegExp(r'\.(pdf|png|jpe?g)$', caseSensitive: false).hasMatch(safeName)
      ? safeName
      : '$safeName.$extension';
  final file = File('${dir.path}/$id-$filename');
  await file.writeAsBytes(bytes, flush: true);
  final result = await OpenFilex.open(file.path);
  if (result.type != ResultType.done) {
    throw ApiError('附件已下载，设备未能打开该类型文件：${result.message}');
  }
}
