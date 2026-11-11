# 一期 Flutter 员工端

同一套简体中文 Dart 代码面向 Android / iOS，不提供员工办公网页。开发工具：Flutter 3.47.9 stable、Dart 3.13.7，SDK 位于 `/home/box/flutter`；Android SDK 位于 `/home/box/Android`，均不入库。

## 使用与地址配置

登录页展开「服务地址与设备」，填写不含 `/api` 的后端地址和设备名称。默认 `http://127.0.0.1:8080`；也接受 `https://oa.example.com`。地址保存在应用偏好设置，登录 token 保存在平台安全存储，密码不保存。首次登录仅能修改密码或退出，改密后所有设备须重新登录。会话固定8小时，收到401返回登录页。

| 运行设备 | 服务地址 / 连接方式 |
| --- | --- |
| 本机接口测试 | `http://127.0.0.1:8080` |
| Android 官方模拟器 | `http://10.0.2.2:8080` |
| USB Android 真机 | 执行 `adb reverse tcp:8080 tcp:8080` 后用默认地址 |
| 同一局域网真机 | `http://电脑局域网IP:8080`，电脑后端需监听可达网卡 |
| Mac 上 iOS 模拟器 | 后端在同一 Mac 时可用 `http://127.0.0.1:8080`；Linux 后端用局域网地址 |
| 日后服务器 | 有效受信任证书的 `https://实际域名` |

当前后端默认只监听127.0.0.1。同一可信局域网联调可在仓库根目录运行 `SERVER_ADDRESS=0.0.0.0 bash scripts/dev-up.sh` 并设置本机防火墙；验证完成后用默认方式重启。真机的127.0.0.1指向手机自身。iOS 开发如被 ATS 拦截本地 HTTP，需在 Mac 的 Debug 配置中设置对应本地测试例外，正式环境使用 HTTPS；不要关闭服务端证书验证。

## 功能

- 工作台：待审批、未读系统通知、未读公告计数及五个常用入口，返回工作台时刷新。
- 打卡：每次提交重新获取前台定位，拒绝无定位、无有效精度；尽力获取 SSID。网络出口 IP 完全由后端识别。正常打卡和出勤的授权仍由现有后端判断。
- 请假/出差/外出/报销：北京时间半天时段、假期类型及证明、抄送同事、报销明细合计和1–30张票据。支持待我审批、我发起的、抄送我的，通过/驳回必填意见、撤回/按规则撤销。
- 五类消息与公告：未读状态、公告详情自动回执、鉴权附件下载并调用设备文件查看器。
- 通讯录：组织树及下级、姓名/工号/部门搜索、分页，只显示在职同事的职务与工作电话。
- 个人中心：资料、假期余额、工作电话、修改密码、查看/退出单台或其他全部设备、退出登录。

## 运行、测试与 Android 构建

```bash
export PATH=/home/box/flutter/bin:$PATH
export ANDROID_HOME=/home/box/Android
# Java 沿用仓库工具链
source scripts/env.sh
cd mobile
flutter pub get
flutter analyze
flutter test
# 用已经完成首次改密的专用测试账号运行真实接口检查
OA_TEST_LOGIN=测试工号 OA_TEST_PASSWORD=测试密码 flutter test test/live_api_test.dart
flutter run -d 设备ID
flutter build apk --debug
cp build/app/outputs/flutter-apk/app-debug.apk ../.runtime/oa-employee-debug.apk
```

本次已经实际生成 `/workspace/oa-system/.runtime/oa-employee-debug.apk`，并使用 `apksigner verify --verbose` 验证签名；为可安装的 debug APK，未做正式签名或商店发布。使用 `/home/box/Android/platform-tools/adb install -r .runtime/oa-employee-debug.apk` 安装，或复制到安卓手机后允许安装该来源并打开。APK、签名文件、SDK、缓存和构建目录不提交。Debug 包允许本地 HTTP，Release 禁用明文 HTTP，正式签名需以后在发布工作站配置。

## iOS 后续构建

已生成 `ios/Runner.xcworkspace` 与 Xcode 工程，最低 iOS 15，含定位用途声明、安全存储及读取 Wi-Fi 信息的 entitlement。本机 Linux 无 Xcode，**未执行 iOS 编译、签名、真机验证，未生成 IPA**。

以后在 Mac 安装匹配 Flutter 和 Xcode，执行 `flutter pub get`、`flutter build ios --simulator` 验证依赖（按 Flutter 提示安装 CocoaPods，如插件需要），用 Xcode 打开 `ios/Runner.xcworkspace`，配置实际 Bundle ID、Team 和签名，再验证安全存储、定位与 Wi-Fi 权限。全员内部安装还需届时具备适用的苹果企业账号或 MDM 分发条件，账号资格、签名及分发渠道均未在本次办理。

## 已知限制与验收边界

- 本机没有连接 Android 真机/模拟器；已验证 Dart 界面状态、API/参数校验、真实服务登录/消息/通讯录和安卓构建。尚未实测系统定位弹窗、SSID、文件选择/打开、安全存储及移动端端到端操作，发放前需在 Android 和 iOS 真机联调。
- SSID 取决于系统权限、平台限制和 iOS provisioning 能力，取不到时不伪造；服务端仍可按已登记出口 IP 判断公司网络。定位和 SSID 是客户端上报，一期不提供反作弊保证。
- 已增加补卡、加班入口与提交、审批、详情和按规则撤回/撤销；打卡记录展示补卡来源，个人中心可刷新调休余额。补卡期限和加班比例读取后台配置。无离线提交、后台定位、真实推送、聊天、员工网页或支付登记。消息需打开页面或刷新查看；设备信息是客户端名称，不是可信硬件标识。
- 本地附件临时文件在应用沙盒中，外部查看器能否打开取决于设备；退出不会清理外部查看器保存的副本。

二期不重打 APK，上述现有 debug 包为一期产物；验证本期修改请运行源码。二期真实接口测试可设 `OA_TEST_LOGIN`、`OA_TEST_PASSWORD`、`OA_TEST_MANAGER`（专用员工及其直属上级的工号，测试密码相同），执行 `flutter test`，未配置时只跳过真实服务用例，其余组件/校验测试照常运行。
