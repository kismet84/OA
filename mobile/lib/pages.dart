import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:network_info_plus/network_info_plus.dart';

import 'api.dart';
import 'attendance.dart';
import 'punch_location.dart';
import 'ui.dart';
import 'validation.dart';
import 'main.dart' show PasswordPage;
import 'requests.dart';
import 'calendar.dart';
import 'tasks.dart';
import 'assets.dart';
import 'contracts.dart';
import 'visitors.dart';
import 'documents.dart';
import 'workbench.dart';

class HomePage extends StatefulWidget {
  final Api api;
  final Json user;
  final Future<void> Function() onLogout;
  final VoidCallback onExpired;
  const HomePage({
    super.key,
    required this.api,
    required this.user,
    required this.onLogout,
    required this.onExpired,
  });
  @override
  State<HomePage> createState() => _HomeState();
}

class _HomeState extends State<HomePage> with Guard<HomePage> {
  Json? summary;
  Json? workbench;
  @override
  void initState() {
    super.initState();
    refresh();
  }

  Future<void> refresh() => run(() async {
    final data = await widget.api.call('/messages/summary') as Json;
    if (mounted) setState(() => summary = data);
    final stats = await widget.api.call('/workbench/summary') as Json;
    if (mounted) setState(() => workbench = stats);
  });
  Future<void> open(Widget page) async {
    await go(context, page);
    if (mounted && widget.api.token != null) refresh();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('工作台'),
      actions: [
        IconButton(
          tooltip: '刷新',
          onPressed: busy ? null : refresh,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: RefreshIndicator(
      onRefresh: refresh,
      child: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            '${widget.user['name']}，你好',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          const SizedBox(height: 8),
          const Text('日常办公，随时处理。业务时间：北京时间'),
          OutlinedButton(
            onPressed: () =>
                open(AttendancePage(api: widget.api, user: widget.user)),
            child: const Text('我的本月考勤'),
          ),
          feedback,
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Wrap(
                spacing: 24,
                runSpacing: 16,
                children: [
                  for (final key in [
                    'approvalsRequests',
                    'approvalsContracts',
                    'approvalsAssets',
                    'tasks',
                    'notifications',
                    'announcements',
                  ])
                    InkWell(
                      onTap: () => open(
                        MessagePage(
                          api: widget.api,
                          user: widget.user,
                          itemType: const {
                            'approvalsRequests': 'REQUEST',
                            'approvalsContracts': 'CONTRACT',
                            'approvalsAssets': 'ASSET',
                            'tasks': 'TASK',
                          }[key],
                          initial: key.startsWith('approvals') || key == 'tasks'
                              ? 'pending'
                              : key,
                        ),
                      ),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            '${summary?[key] ?? '—'}',
                            style: Theme.of(context).textTheme.headlineMedium,
                          ),
                          Text(
                            key == 'approvalsRequests'
                                ? '申请待审批'
                                : key == 'approvalsContracts'
                                ? '合同待审批'
                                : key == 'approvalsAssets'
                                ? '资产待审批'
                                : key == 'tasks'
                                ? '待办任务'
                                : key == 'notifications'
                                ? '未读通知'
                                : '未读公告',
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ),
          Card(
            child: ListTile(
              title: const Text('考勤与审批汇总'),
              subtitle: Text(
                workbench == null
                    ? '查看本人考勤与审批时效'
                    : '本月正常打卡 ${workbench!['attendance']['normalPunches']} 次 · 出勤 ${workbench!['attendance']['fieldPunches']} 次\n有记录 ${workbench!['attendance']['recordedDays']} 天 · 办结 ${workbench!['approvals']['completed']} 单',
              ),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => open(WorkbenchPage(api: widget.api)),
            ),
          ),
          section('常用功能'),
          for (final entry in <(String, IconData, Widget)>[
            ('访客管理', Icons.people_outline, VisitorsPage(api: widget.api)),
            (
              '合同管理',
              Icons.description_outlined,
              ContractsPage(api: widget.api),
            ),
            ('资产领用', Icons.inventory_2_outlined, AssetsPage(api: widget.api)),
            (
              '文档资料',
              Icons.description_outlined,
              DocumentsPage(api: widget.api),
            ),
            (
              '待办任务',
              Icons.task_alt,
              TasksPage(api: widget.api, user: widget.user),
            ),
            (
              '日程与会议',
              Icons.calendar_month,
              CalendarPage(api: widget.api, user: widget.user),
            ),
            ('打卡', Icons.location_on_outlined, PunchPage(api: widget.api)),
            (
              '补卡',
              Icons.edit_calendar_outlined,
              RequestFormPage(api: widget.api, initialKind: 'CORRECTION'),
            ),
            (
              '加班',
              Icons.more_time,
              RequestFormPage(api: widget.api, initialKind: 'OVERTIME'),
            ),
            (
              '审批与申请',
              Icons.fact_check_outlined,
              MessagePage(
                api: widget.api,
                user: widget.user,
                initial: 'pending',
                approval: true,
              ),
            ),
            (
              '消息中心',
              Icons.notifications_outlined,
              MessagePage(api: widget.api, user: widget.user),
            ),
            ('通讯录', Icons.contacts_outlined, DirectoryPage(api: widget.api)),
            (
              '个人中心',
              Icons.person_outline,
              ProfilePage(
                api: widget.api,
                onLogout: widget.onLogout,
                onExpired: widget.onExpired,
              ),
            ),
          ])
            Card(
              child: ListTile(
                leading: Icon(entry.$2, color: const Color(0xff176b64)),
                title: Text(entry.$1),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => open(entry.$3),
              ),
            ),
        ],
      ),
    ),
  );
}

class PunchPage extends StatefulWidget {
  final Api api;
  const PunchPage({super.key, required this.api});
  @override
  State<PunchPage> createState() => _PunchState();
}

class _PunchState extends State<PunchPage> with Guard<PunchPage> {
  String kind = 'NORMAL';
  Position? position;
  DateTime? locatedAt;
  String? locationSource;
  String? ssid;
  List<Json> history = [];
  @override
  void initState() {
    super.initState();
    startPunchLocationWarmup();
    load();
  }

  @override
  void dispose() {
    stopPunchLocationWarmup();
    super.dispose();
  }

  Future<void> load() => run(() async {
    history = records(await widget.api.call('/punches'));
  });
  Future<void> locate() async {
    position = null;
    locatedAt = null;
    locationSource = null;
    ssid = null;
    final location = await getPunchLocation(
      allowStaleLastKnown: kind == 'NORMAL',
    );
    final value = location.position;
    punchPayload(
      kind,
      value.latitude,
      value.longitude,
      value.accuracy,
      locationSource: location.locationSource,
      locatedAt: location.locatedAt,
    );
    position = value;
    locatedAt = location.locatedAt;
    locationSource = location.locationSource;
    try {
      ssid = await NetworkInfo().getWifiName();
      if (ssid == '<unknown ssid>') ssid = null;
      ssid = ssid?.replaceAll(RegExp(r'^"|"$'), '');
    } catch (_) {
      ssid = null;
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('打卡')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        const Text('正常打卡须在公司网络；出勤须有当前有效的已批准出差/外出申请。只记录打卡，不计算迟到早退。'),
        const SizedBox(height: 16),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'NORMAL', label: Text('正常打卡')),
            ButtonSegment(value: 'FIELD', label: Text('出勤')),
          ],
          selected: {kind},
          onSelectionChanged: busy
              ? null
              : (v) => setState(() => kind = v.first),
        ),
        feedback,
        if (position != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '${locationSourceLabel(locationSource)} · 采集时间 ${timeOf(locatedAt?.toIso8601String())}\n纬度 ${position!.latitude.toStringAsFixed(6)}，经度 ${position!.longitude.toStringAsFixed(6)}\n精度 ${position!.accuracy.toStringAsFixed(1)} 米\nWi-Fi：${ssid ?? '未能获取，由服务器校验出口 IP'}',
              ),
            ),
          ),
        FilledButton.icon(
          icon: const Icon(Icons.my_location),
          label: const Text('获取定位并打卡'),
          onPressed: busy
              ? null
              : () => run(() async {
                  await locate();
                  if (!context.mounted) return;
                  setState(() {});
                  final confirmed = await showDialog<bool>(
                    context: context,
                    builder: (context) => AlertDialog(
                      title: const Text('确认定位并打卡'),
                      content: Text(
                        '${locationSourceLabel(locationSource)}\n采集时间：${timeOf(locatedAt?.toIso8601String())}',
                      ),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(context, false),
                          child: const Text('取消'),
                        ),
                        FilledButton(
                          onPressed: () => Navigator.pop(context, true),
                          child: const Text('确认打卡'),
                        ),
                      ],
                    ),
                  );
                  if (confirmed != true) return;
                  final p = position!;
                  await widget.api.call(
                    '/punches',
                    method: 'POST',
                    data: punchPayload(
                      kind,
                      p.latitude,
                      p.longitude,
                      p.accuracy,
                      ssid: ssid,
                      locatedAt: locatedAt,
                      locationSource: locationSource,
                    ),
                  );
                  history = records(await widget.api.call('/punches'));
                }, success: '打卡成功'),
        ),
        TextButton(
          onPressed: () => Geolocator.openAppSettings(),
          child: const Text('打开系统应用设置'),
        ),
        section('最近打卡（最多200条）'),
        if (history.isEmpty) empty('暂无打卡记录'),
        for (final p in history)
          Card(
            child: ListTile(
              title: Text(textOf(p['kind'])),
              subtitle: Text(
                '${timeOf(p['punched_at'])} · ${p['correction_request_id'] == null ? '现场打卡' : '补卡 #${p['correction_request_id']}'}\n定位：${locationSourceLabel(p['locationSource'] ?? p['location_source'])}${p['locatedAt'] != null || p['located_at'] != null ? ' · 采集时间 ${timeOf(p['locatedAt'] ?? p['located_at'])}' : ''}',
              ),
            ),
          ),
      ],
    ),
  );
}

class MessagePage extends StatefulWidget {
  final Api api;
  final Json user;
  final String initial;
  final bool approval;
  final String? itemType;
  const MessagePage({
    super.key,
    required this.api,
    required this.user,
    this.initial = 'notifications',
    this.approval = false,
    this.itemType,
  });
  @override
  State<MessagePage> createState() => _MessageState();
}

class _MessageState extends State<MessagePage> with Guard<MessagePage> {
  late String category = widget.initial;
  List<Json> rows = [];
  int offset = 0;
  List<String> get categories => widget.approval
      ? ['pending', 'initiated', 'cc']
      : ['pending', 'initiated', 'cc', 'notifications', 'announcements'];
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() => run(() async {
    final data = records(
      await widget.api.call(
        '/messages?category=$category&offset=$offset${category == 'pending' && widget.itemType != null ? '&itemType=${widget.itemType}' : ''}',
      ),
    );
    if (mounted) setState(() => rows = data);
  });
  Future<void> open(Json row) async {
    if (category == 'announcements') {
      await go(context, AnnouncementPage(api: widget.api, id: row['id']));
    } else if (category == 'notifications') {
      await run(() async {
        if (row['read_at'] == null) {
          await widget.api.call(
            '/notifications/${row['id']}/read',
            method: 'POST',
          );
        }
        if (!mounted) return;
        if (row['contract_id'] != null) {
          await go(
            context,
            ContractDetailPage(api: widget.api, id: row['contract_id'] as int),
          );
        } else if (row['asset_request_id'] != null) {
          await go(
            context,
            AssetDetailPage(
              api: widget.api,
              id: row['asset_request_id'] as int,
            ),
          );
        } else if (row['document_id'] != null) {
          await go(
            context,
            DocumentPage(api: widget.api, id: row['document_id'] as int),
          );
        } else if (row['task_id'] != null) {
          await go(
            context,
            TaskPage(
              api: widget.api,
              user: widget.user,
              id: row['task_id'] as int,
            ),
          );
        } else if (row['meeting_id'] != null) {
          await go(
            context,
            MeetingPage(
              api: widget.api,
              user: widget.user,
              id: row['meeting_id'] as int,
            ),
          );
        } else if (row['request_id'] != null) {
          await go(
            context,
            RequestDetailPage(
              api: widget.api,
              id: row['request_id'],
              user: widget.user,
            ),
          );
        } else {
          await showDialog<void>(
            context: context,
            builder: (c) => AlertDialog(
              title: const Text('系统通知'),
              content: SingleChildScrollView(child: Text('${row['message']}')),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(c),
                  child: const Text('关闭'),
                ),
              ],
            ),
          );
        }
      });
    } else if (row['item_type'] == 'CONTRACT') {
      await go(
        context,
        ContractDetailPage(api: widget.api, id: row['id'] as int),
      );
    } else if (row['item_type'] == 'ASSET') {
      await go(context, AssetDetailPage(api: widget.api, id: row['id'] as int));
    } else if (row['item_type'] == 'TASK') {
      await go(
        context,
        TaskPage(api: widget.api, user: widget.user, id: row['task_id'] as int),
      );
    } else {
      await go(
        context,
        RequestDetailPage(api: widget.api, id: row['id'], user: widget.user),
      );
    }
    if (mounted && widget.api.token != null) load();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.approval ? '审批与申请' : '消息中心'),
      actions: [
        IconButton(
          tooltip: '刷新',
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    floatingActionButton: FloatingActionButton.extended(
      onPressed: busy
          ? null
          : () async {
              await go(context, RequestFormPage(api: widget.api));
              if (mounted) load();
            },
      icon: const Icon(Icons.add),
      label: const Text('提交申请'),
    ),
    body: Column(
      children: [
        SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          padding: const EdgeInsets.all(12),
          child: Row(
            children: [
              for (final c in categories)
                Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: ChoiceChip(
                    label: Text(labels[c]!),
                    selected: category == c,
                    onSelected: busy
                        ? null
                        : (_) {
                            setState(() {
                              category = c;
                              offset = 0;
                              rows = [];
                            });
                            load();
                          },
                  ),
                ),
            ],
          ),
        ),
        feedback,
        Expanded(
          child: RefreshIndicator(
            onRefresh: load,
            child: ListView(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 100),
              children: [
                if (rows.isEmpty && !busy) empty('暂无${labels[category]}'),
                for (final r in rows)
                  Card(
                    child: ListTile(
                      onTap: () => open(r),
                      leading: Icon(
                        category == 'announcements'
                            ? Icons.campaign_outlined
                            : category == 'notifications'
                            ? Icons.mail_outline
                            : Icons.description_outlined,
                        color: const Color(0xff176b64),
                      ),
                      title: Text(
                        '${r['item_type'] == 'CONTRACT'
                            ? '合同 · '
                            : r['item_type'] == 'ASSET'
                            ? '资产领用 · '
                            : r['item_type'] == 'TASK'
                            ? '任务 · '
                            : r['item_type'] == 'REQUEST'
                            ? '审批单 · '
                            : ''}${r['title'] ?? r['message'] ?? '${textOf(r['kind'])} · ${r['user_name']}'}',
                      ),
                      subtitle: Text(
                        category == 'announcements' ||
                                category == 'notifications'
                            ? '${r['read_at'] == null ? '未读' : '已读'} · ${timeOf(r['published_at'] ?? r['created_at'])}'
                            : r['item_type'] == 'ASSET'
                            ? '${assetStates[r['status']]} · ${r['user_name']}'
                            : r['item_type'] == 'TASK'
                            ? '${taskStates[r['status']]} · 截止：${timeOf(r['due_at'])}${r['overdue'] == true ? '（已过期）' : ''}'
                            : '${textOf(r['status'])} · ${r['reason'] ?? ''}\n${timeOf(r['created_at'])}',
                      ),
                      trailing: const Icon(Icons.chevron_right),
                    ),
                  ),
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    TextButton(
                      onPressed: offset == 0 || busy
                          ? null
                          : () {
                              offset -= 200;
                              load();
                            },
                      child: const Text('上一页'),
                    ),
                    Text('第 ${offset ~/ 200 + 1} 页'),
                    TextButton(
                      onPressed: rows.length < 200 || busy
                          ? null
                          : () {
                              offset += 200;
                              load();
                            },
                      child: const Text('下一页'),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ],
    ),
  );
}

class AnnouncementPage extends StatefulWidget {
  final Api api;
  final int id;
  const AnnouncementPage({super.key, required this.api, required this.id});
  @override
  State<AnnouncementPage> createState() => _AnnouncementState();
}

class _AnnouncementState extends State<AnnouncementPage>
    with Guard<AnnouncementPage> {
  Json? data;
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() => run(() async {
    data = await widget.api.call('/announcements/${widget.id}');
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('公告'),
      actions: [
        IconButton(
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        feedback,
        if (data != null) ...[
          Text(
            '${data!['title']}',
            style: Theme.of(context).textTheme.headlineSmall,
          ),
          Text(timeOf(data!['published_at'])),
          const Divider(),
          SelectableText('${data!['body']}'),
          section('附件'),
          for (final f in records(data!['attachments']))
            ListTile(
              leading: const Icon(Icons.download),
              title: Text('${f['original_name']}'),
              onTap: busy
                  ? null
                  : () => run(
                      () => openAttachment(
                        widget.api,
                        '${f['id']}',
                        '${f['original_name']}',
                      ),
                    ),
            ),
          const Text('打开公告已自动记录已读回执。'),
        ],
      ],
    ),
  );
}

class DirectoryPage extends StatefulWidget {
  final Api api;
  const DirectoryPage({super.key, required this.api});
  @override
  State<DirectoryPage> createState() => _DirectoryState();
}

class _DirectoryState extends State<DirectoryPage> with Guard<DirectoryPage> {
  final search = TextEditingController();
  List<Json> departments = [], people = [];
  int? department;
  int offset = 0;
  @override
  void initState() {
    super.initState();
    load(initial: true);
  }

  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  List<Json> get tree {
    final result = <Json>[];
    void walk(dynamic parent, int depth) {
      for (final d in departments.where((d) => d['parent_id'] == parent)) {
        result.add({...d, 'label': '${'　' * depth}${d['name']}'});
        walk(d['id'], depth + 1);
      }
    }

    walk(null, 0);
    return result;
  }

  Future<void> load({bool initial = false}) => run(() async {
    if (initial) {
      departments = records(await widget.api.call('/directory/departments'));
    }
    people = records(
      await widget.api.call(
        '/directory?search=${Uri.encodeQueryComponent(search.text)}&offset=$offset${department == null ? '' : '&departmentId=$department'}',
      ),
    );
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('通讯录')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        field(search, '搜索姓名、工号或部门', max: 100),
        DropdownButtonFormField<int?>(
          initialValue: department,
          decoration: const InputDecoration(labelText: '组织部门（包含下级）'),
          items: [
            const DropdownMenuItem<int?>(value: null, child: Text('全部部门')),
            for (final d in tree)
              DropdownMenuItem<int?>(value: d['id'], child: Text(d['label'])),
          ],
          onChanged: busy
              ? null
              : (v) {
                  setState(() {
                    department = v;
                    offset = 0;
                  });
                  load();
                },
        ),
        FilledButton(
          onPressed: busy
              ? null
              : () {
                  offset = 0;
                  load();
                },
          child: const Text('查询'),
        ),
        feedback,
        if (people.isEmpty && !busy) empty('未找到在职同事'),
        for (final p in people)
          Card(
            child: ListTile(
              leading: CircleAvatar(
                child: Text('${p['name']}'.substring(0, 1)),
              ),
              title: Text('${p['name']} · ${p['employee_no']}'),
              subtitle: Text(
                '${p['department_name']} · ${textOf(p['job_title'])}\n工作电话：${textOf(p['work_phone'])}',
              ),
              isThreeLine: true,
            ),
          ),
        Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            TextButton(
              onPressed: offset == 0 || busy
                  ? null
                  : () {
                      offset -= 200;
                      load();
                    },
              child: const Text('上一页'),
            ),
            Text('第 ${offset ~/ 200 + 1} 页'),
            TextButton(
              onPressed: people.length < 200 || busy
                  ? null
                  : () {
                      offset += 200;
                      load();
                    },
              child: const Text('下一页'),
            ),
          ],
        ),
      ],
    ),
  );
}

class ProfilePage extends StatefulWidget {
  final Api api;
  final Future<void> Function() onLogout;
  final VoidCallback onExpired;
  const ProfilePage({
    super.key,
    required this.api,
    required this.onLogout,
    required this.onExpired,
  });
  @override
  State<ProfilePage> createState() => _ProfileState();
}

class _ProfileState extends State<ProfilePage> with Guard<ProfilePage> {
  Json? data;
  List<Json> balances = [];
  final phone = TextEditingController();
  @override
  void initState() {
    super.initState();
    load();
  }

  @override
  void dispose() {
    phone.dispose();
    super.dispose();
  }

  Future<void> load() => run(() async {
    data = await widget.api.call('/profile');
    phone.text = data!['work_phone'] ?? '';
    balances = records(await widget.api.call('/balances'));
  });
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('个人中心'),
      actions: [
        IconButton(
          tooltip: '刷新余额',
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        feedback,
        if (data != null) ...[
          section('${data!['name']}'),
          Text(
            '工号：${data!['employee_no']}\n部门：${data!['department_name']}\n职务：${textOf(data!['job_title'])}\n角色：${'${data!['roles']}'.split(',').map(textOf).join('、')}',
          ),
          field(phone, '工作电话（对同事公开）', max: 40, keyboard: TextInputType.phone),
          FilledButton(
            onPressed: busy
                ? null
                : () => run(() async {
                    if (!RegExp(r'^[0-9+() -]{0,40}$').hasMatch(phone.text)) {
                      throw ApiError('工作电话仅支持数字、空格、+、-、括号');
                    }
                    await widget.api.call(
                      '/profile/work-phone',
                      method: 'PUT',
                      data: {'workPhone': phone.text.trim()},
                    );
                  }, success: '工作电话已更新'),
            child: const Text('保存工作电话'),
          ),
        ],
        section('假期余额'),
        if (balances.isEmpty && !busy) const Text('暂无登记余额，请联系管理员'),
        for (final b in balances)
          Card(
            child: ListTile(
              title: Text('${b['type_name']} · ${b['year']}年'),
              trailing: Text('${b['amount']} 天'),
            ),
          ),
        const Divider(),
        ListTile(
          leading: const Icon(Icons.lock_outline),
          title: const Text('修改密码'),
          onTap: () => go(
            context,
            PasswordPage(api: widget.api, onChanged: widget.onExpired),
          ),
        ),
        ListTile(
          leading: const Icon(Icons.devices),
          title: const Text('登录设备'),
          onTap: () => go(
            context,
            SessionPage(api: widget.api, onExpired: widget.onExpired),
          ),
        ),
        TextButton(
          onPressed: busy
              ? null
              : () => run(() async {
                  if (await confirm(context, '退出登录', '确认退出当前设备？')) {
                    await widget.onLogout();
                  }
                }),
          child: const Text('退出登录'),
        ),
      ],
    ),
  );
}

class SessionPage extends StatefulWidget {
  final Api api;
  final VoidCallback onExpired;
  const SessionPage({super.key, required this.api, required this.onExpired});
  @override
  State<SessionPage> createState() => _SessionState();
}

class _SessionState extends State<SessionPage> with Guard<SessionPage> {
  List<Json> sessions = [];
  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() => run(() async {
    sessions = records(await widget.api.call('/auth/sessions'));
  });
  Future<void> revoke(String id, bool current) => run(() async {
    if (!await confirm(
      context,
      '退出设备',
      id == 'others' ? '确认退出除当前设备外的全部设备？' : '该设备须重新登录才能继续使用。',
    )) {
      return;
    }
    await widget.api.call('/auth/sessions/$id', method: 'DELETE');
    if (current) {
      widget.api.token = null;
      widget.onExpired();
    } else {
      sessions = records(await widget.api.call('/auth/sessions'));
    }
  }, success: '设备已退出');
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('登录设备'),
      actions: [
        IconButton(
          onPressed: busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        feedback,
        const Text('会话有效期8小时；修改密码后全部旧会话失效。'),
        for (final s in sessions)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${s['device_name']} · ${s['platform']}${s['current'] == true ? '（当前设备）' : ''}',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  Text(
                    '登录：${timeOf(s['created_at'])}\n到期：${timeOf(s['expires_at'])}',
                  ),
                  TextButton(
                    onPressed: busy
                        ? null
                        : () => revoke(s['id'], s['current'] == true),
                    child: const Text('退出此设备'),
                  ),
                ],
              ),
            ),
          ),
        OutlinedButton(
          onPressed: busy ? null : () => revoke('others', false),
          child: const Text('退出其他全部设备'),
        ),
      ],
    ),
  );
}
