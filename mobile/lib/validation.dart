import 'dart:convert';

import 'api.dart';

DateTime shanghaiNow() => DateTime.now().toUtc().add(const Duration(hours: 8));
int cents(String input) {
  if (!RegExp(r'^\d{1,8}(\.\d{1,2})?$').hasMatch(input.trim())) {
    throw ApiError('金额须为正数，最多两位小数');
  }
  final parts = input.trim().split('.');
  final value =
      int.parse(parts[0]) * 100 +
      (parts.length == 2 ? int.parse(parts[1].padRight(2, '0')) : 0);
  if (value < 1 || value > 9999999900) throw ApiError('金额须在 0.01–99999999 元之间');
  return value;
}

void validatePassword(String old, String next) {
  if (old.isEmpty || next.length < 10 || utf8.encode(next).length > 72) {
    throw ApiError('原密码必填；新密码至少10位，最多72个 UTF-8 字节');
  }
  if (old == next) throw ApiError('新密码不能与原密码相同');
}

Json punchPayload(
  String kind,
  double? latitude,
  double? longitude,
  double? accuracy, {
  String? ssid,
  DateTime? locatedAt,
  String? locationSource,
}) {
  if (!['NORMAL', 'FIELD'].contains(kind) ||
      latitude == null ||
      longitude == null ||
      accuracy == null ||
      !latitude.isFinite ||
      !longitude.isFinite ||
      !accuracy.isFinite ||
      latitude.abs() > 90 ||
      longitude.abs() > 180 ||
      accuracy <= 0 ||
      accuracy > 200) {
    throw ApiError('必须成功获取有效定位，精度须大于0且不超过200米');
  }
  if (locationSource == null ||
      locatedAt == null ||
      ![
        'LIVE',
        'WARMUP_CACHE',
        'LAST_KNOWN',
        'STALE_FALLBACK',
      ].contains(locationSource)) {
    throw ApiError('定位来源或采集时间缺失或不支持，请升级 App 或改用员工网页');
  }
  final age = DateTime.now().difference(locatedAt);
  if (age > const Duration(minutes: 5) || age < const Duration(minutes: -2)) {
    throw ApiError('定位采集时间须在5分钟内，且不得超过未来2分钟，请重新定位');
  }
  if (kind == 'FIELD' && locationSource == 'STALE_FALLBACK') {
    throw ApiError('外出打卡不允许使用超时旧缓存');
  }
  return {
    'kind': kind,
    'latitude': latitude,
    'longitude': longitude,
    'accuracy': accuracy,
    if (ssid != null && ssid.isNotEmpty) 'ssid': ssid,
    'locatedAt': locatedAt.toUtc().toIso8601String(),
    'locationSource': locationSource,
  };
}

Json requestPayload({
  required String kind,
  required String reason,
  int? typeId,
  String? startAt,
  String? endAt,
  String? punchKind,
  String? amount,
  List<Json> items = const [],
  List<String> attachmentIds = const [],
  String? attachmentId,
  List<int> cc = const [],
  bool proofRequired = false,
}) {
  if (![
    'LEAVE',
    'TRAVEL',
    'OUTING',
    'EXPENSE',
    'OVERTIME',
    'CORRECTION',
  ].contains(kind)) {
    throw ApiError('申请类型无效');
  }
  if (reason.trim().isEmpty || reason.length > 2000) {
    throw ApiError('申请理由必填，最多2000字');
  }
  if (cc.length > 100) throw ApiError('抄送最多100人');
  final Json result = {
    'kind': kind,
    'reason': reason.trim(),
    'ccUserIds': cc.toSet().toList(),
  };
  if (kind == 'CORRECTION') {
    final at = DateTime.tryParse(startAt ?? '');
    final now = DateTime.parse(
      shanghaiNow().toIso8601String().replaceAll('Z', ''),
    );
    if (at == null ||
        at.isAfter(now) ||
        !['NORMAL', 'FIELD'].contains(punchKind)) {
      throw ApiError('请选择过去的补卡时间及打卡类型');
    }
    result.addAll({'startAt': startAt, 'punchKind': punchKind});
  } else if (kind == 'EXPENSE') {
    final total = cents(amount ?? '');
    if (items.isEmpty || items.length > 100) throw ApiError('报销需1–100行费用明细');
    int sum = 0;
    final lines = items.map((i) {
      final description = '${i['description']}'.trim();
      if (description.isEmpty || description.length > 200) {
        throw ApiError('明细用途必填且最多200字');
      }
      final value = cents('${i['amount']}');
      sum += value;
      return {'description': description, 'amount': value / 100};
    }).toList();
    if (sum != total) throw ApiError('明细合计必须等于报销总额');
    if (attachmentIds.isEmpty ||
        attachmentIds.length > 30 ||
        attachmentIds.toSet().length != attachmentIds.length) {
      throw ApiError('请上传1–30张不重复票据');
    }
    result.addAll({
      'amount': total / 100,
      'items': lines,
      'attachmentIds': attachmentIds,
    });
  } else {
    if (kind == 'LEAVE' && typeId == null) throw ApiError('请选择假期类型');
    final start = DateTime.tryParse(startAt ?? '');
    final end = DateTime.tryParse(endAt ?? '');
    if (start == null ||
        end == null ||
        !end.isAfter(start) ||
        (kind != 'OVERTIME' && start.year != end.year) ||
        ![9, 13].contains(start.hour) ||
        ![13, 18].contains(end.hour) ||
        start.minute != 0 ||
        end.minute != 0) {
      throw ApiError(kind == 'OVERTIME' ? '请选择有效的半天起止时段' : '请选择同一年内有效的半天起止时段');
    }
    // Submitted strings describe Shanghai wall time, independent of the phone timezone.
    if (kind != 'OVERTIME' &&
        !end.isAfter(
          DateTime.parse(shanghaiNow().toIso8601String().replaceAll('Z', '')),
        )) {
      throw ApiError('结束时间须在未来');
    }
    if (proofRequired && attachmentId == null) throw ApiError('此假期必须上传证明');
    result.addAll({
      'typeId': kind == 'LEAVE' ? typeId : null,
      'startAt': startAt,
      'endAt': endAt,
      'attachmentId': attachmentId,
    });
  }
  return result;
}

void validateDecision(bool approve, String comment) {
  if (!approve && comment.trim().isEmpty) throw ApiError('驳回必须填写意见');
  if (comment.length > 1000) throw ApiError('审批意见最多1000字');
}

// API timestamps carry +08:00; compare instants to work on phones in every timezone.
bool canCancelApproved(Json detail, {DateTime? now}) {
  if (detail['status'] != 'APPROVED' ||
      ['EXPENSE', 'CORRECTION'].contains(detail['kind'])) {
    return false;
  }
  final start = DateTime.tryParse('${detail['start_at']}');
  return start != null &&
      start.toUtc().isAfter((now ?? DateTime.now()).toUtc());
}

String locationSourceLabel(Object? source) =>
    const {
      'LIVE': '现场实时',
      'WARMUP_CACHE': '预热缓存',
      'LAST_KNOWN': '使用最近一次定位（系统缓存）',
      'STALE_FALLBACK': '使用最近一次定位（超时旧缓存）',
      'CORRECTION': '补卡',
      'UNKNOWN': '未知/历史记录',
    }[source] ??
    '未知/历史记录';
