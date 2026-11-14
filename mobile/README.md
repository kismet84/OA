# 一期 Flutter 员工端

同一套简体中文 Dart 代码面向 Android / iOS；浏览器员工办公入口由仓库 Vue 工程提供，见[根目录说明](../README.md#二期员工办公网页)。开发工具：Flutter 3.47.9 stable、Dart 3.13.7，SDK 位于 `/home/box/flutter`；Android SDK 位于 `/home/box/Android`，均不入库。

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
- 打卡：Flutter 使用 high 精度，首选定位与 forceLocationManager 系统通道并行，各约 45 秒；先取合格实时结果，胜出后取消剩余原生请求，页面退出、会话退出、重试也取消。筛选精度大于 0 且不超过 200 米、有效坐标及 5 分钟内采集时间（最多容忍未来 2 分钟）。实时失败后仅使用 5 分钟内合格预热/系统缓存，正常卡和出勤均拒绝更旧缓存；保留采集时刻和来源，缓存须确认后提交。无合格位置提示超时且不提交；SSID 获取失败仍交服务端校验出口 IP。
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

历史一期（2026-11-11，员工网页基线 aab20c0 之前）曾生成 `/workspace/oa-system/.runtime/oa-employee-debug.apk`，并使用 `apksigner verify --verbose` 验证签名；为可安装的 debug APK，未做正式签名或商店发布。使用 `/home/box/Android/platform-tools/adb install -r .runtime/oa-employee-debug.apk` 安装，或复制到安卓手机后允许安装该来源并打开。APK、签名文件、SDK、缓存和构建目录不提交。Debug 包允许本地 HTTP，Release 禁用明文 HTTP，正式签名配置已加入；机外密钥仍须由运维提供，见下文 P1-04。

## iOS 后续构建

已生成 `ios/Runner.xcworkspace` 与 Xcode 工程，最低 iOS 15，含定位用途声明、安全存储及读取 Wi-Fi 信息的 entitlement。本机 Linux 无 Xcode，**未执行 iOS 编译、签名、真机验证，未生成 IPA**。

以后在 Mac 安装匹配 Flutter 和 Xcode，执行 `flutter pub get`、`flutter build ios --simulator` 验证依赖（按 Flutter 提示安装 CocoaPods，如插件需要），用 Xcode 打开 `ios/Runner.xcworkspace`，配置实际 Bundle ID、Team 和签名，再验证安全存储、定位与 Wi-Fi 权限。全员内部安装还需届时具备适用的苹果企业账号或 MDM 分发条件，账号资格、签名及分发渠道均未在本次办理。

## 已知限制与验收边界

- 历史本机验收没有连接 Android 真机/模拟器；当时已验证 Dart 界面状态、API/参数校验、真实服务登录/消息/通讯录和安卓构建。尚未实测系统定位弹窗、SSID、文件选择/打开、安全存储及移动端端到端操作，发放前需在 Android 和 iOS 真机联调。
- SSID 取决于系统权限、平台限制和 iOS provisioning 能力，取不到时不伪造；服务端仍可按已登记出口 IP 判断公司网络。定位和 SSID 是客户端上报，一期不提供反作弊保证。
- 已增加补卡、加班入口与提交、审批、详情和按规则撤回/撤销；打卡记录展示补卡来源，个人中心可刷新调休余额。补卡期限和加班比例读取后台配置。无离线提交、后台定位、聊天或移动端支付登记。员工网页已由 Vue `/staff/` 提供；Android 配置完整时可接收 FCM，消息仍支持打开页面或刷新查看；设备信息是客户端名称，不是可信硬件标识。
- 本地附件临时文件在应用沙盒中，外部查看器能否打开取决于设备；退出不会清理外部查看器保存的副本。

上述 debug 包是历史一期产物，不能代表当前源码；本批未打 APK，移动端改动后续需重新构建并验包。二期真实接口测试可设 `OA_TEST_LOGIN`、`OA_TEST_PASSWORD`、`OA_TEST_MANAGER`（专用员工及其直属上级的工号，测试密码相同），执行 `flutter test`，未配置时只跳过真实服务用例，其余组件/校验测试照常运行。

## 文档资料（本期增量）

工作台「文档资料」可分类浏览、按标题和上海发布日期筛选；打开当前可见文档，PDF逐页预览、图片直接预览。预览图片由服务端烧录查看人姓名、工号、上海日期；首次成功预览记已读。允许下载时保存并打开服务端水印PDF/PNG，禁止下载时隐藏入口，服务端同步拒绝导出。系统通知可进入对应文档，已下架或移出范围显示中文原因。

员工不上传公司文档；发布由管理员后台完成，接收范围及下载许可实时按服务端判断。预览不取原件；水印PDF下载为栅格版，下载图片统一PNG。禁止下载不等于禁止截图或保存水印预览。`documents_test.dart` 覆盖筛选、两页预览、禁止下载、二进制403和通知失效提示；继续运行全部现有测试，该段为文档模块历史验收，不代表当前安装包；不制作正式签名或IPA。


## 安卓 FCM（V12）

仅 Android 接入 `firebase_core` / `firebase_messaging`，已配置 Google services 插件及公开客户端 `android/app/google-services.json`，包名 `com.company.oa_employee`。不初始化 iOS Firebase、不接入 APNs，不涉及苹果账号、签名或 IPA。

首次登录且完成改密后申请通知权限（安卓13+），拒绝不会阻断其他功能，之后不反复弹窗；可在系统设置手动授权并重新登录。登录或恢复会话后向 `POST /push/tokens` 上报，token 刷新时重新登记并注销旧 token；退出先调用 `DELETE /push/tokens` 再退出会话。网络、Firebase 或权限故障均不阻断员工业务。前台推送给中文提示及「查看」入口，后台/冷启动点击进入消息中心，再从通知行进入已有详情。

必须在服务端配置 `FCM_CREDENTIALS`（或 `GOOGLE_APPLICATION_CREDENTIALS`）才会真实发送。不要将服务账号、私钥放入移动工程；客户端 JSON 不含服务端密钥。设备需 Google Play services 及 FCM 网络连接，国内厂商通道未接入。公告维持原接口查询，系统通知在消息中心手动刷新可见，推送不替代站内记录。

`flutter test` 包含 `push_test.dart` 假设备/HTTP 测试，不连接真实 Firebase；`flutter analyze --no-fatal-infos` 可保留既有测试提示。本段为 V12 历史说明，当前安装包是否含后续修改须验包；本批未进行真机 FCM 投递验收。


### P1-01 / P1-04 整改口径

- P1-01：三端统一要求精度大于 0 且不超过 200 米，所有定位来源均须在 5 分钟内（以服务端时钟为准，最多容忍未来 2 分钟）。来源与采集时间必填；缺字段或显式 UNKNOWN 均拒绝，须升级 App 或改用 `/staff/` 员工网页。正常卡和外出卡均不接受超过 5 分钟的缓存；FIELD 仍禁用 STALE_FALLBACK。补卡审批生成 CORRECTION、无定位的路径保持不变。真机定位未核实。
- P1-04：Android release 已配置机外签名，保持 `oaCleartext=false`、禁止明文 HTTP，无调试签名回退。由运维在仓库外生成、保管并备份正式签名密钥，通过 `OA_ANDROID_KEYSTORE_PATH`、`OA_ANDROID_STORE_PASSWORD`、`OA_ANDROID_KEY_PASSWORD`、`OA_ANDROID_KEY_ALIAS` 提供配置；不得放在仓库内。缺配置或文件时 release 明确失败，debug 不需要正式密钥。现网 2001–2004 使用调试证书，员工换正式包必须**卸载后重装**并重新登录。仓库不包含密钥，本批未打 APK、未在真机验签，不代表现网 HTTPS 被降级。

本轮定位取消与网络超时的真机效果未核实；现网超时未复现。Caddy 仅改仓库样例，现网响应头未核实，本批未部署 Mac。

普通 API 的 30 秒、上传的 60 秒、文档读取的 90 秒和附件下载的 60 秒均覆盖响应头及完整响应体，超时会中止请求并清理上传进度。写操作超时表示结果未知，应先查询单据再决定是否重试。

签名配置验收可运行 `source scripts/env.sh && python3 scripts/verify-android-signing.py`，或 `cd mobile/android && ./gradlew :app:verifyReleaseSigning --dry-run`（未提供签名环境变量时应明确失败）；`:app:validateSigningDebug --dry-run` 不要求正式密钥。本批不生成 keystore，不运行安装包构建任务。签名文件及本机 secrets 目录由 `.gitignore` 排除。
