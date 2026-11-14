# 一期 MVP 本机验收记录

历史档案（2026-11-11）：各节仅适用于其标明的提交/日期；一期初始记录未记录精确提交，不得视为当前生产验收。公告增量基于 `2f17099` 及当时工作区，员工网页前基线见 `aab20c0`。后续已提供员工网页现场打卡、日程/任务/文档、月度迟到/早退/缺卡判定及 Android FCM；下文“无员工网页/无推送”等旧限制已被取代。当前能力见根 README；旧 HTTPS/设备结论不代表本次 Mac 现网或真机验证。

## 公告、消息中心与抄送增量验收（2026-11-11）

在提交 `2f17099` 及续跑时已有未提交改动上增量完成，使用本仓库 JDK 21、PostgreSQL、Redis 及原上传目录。Flyway V3 保留原数据；执行 `bash scripts/dev-up.sh` 重新打包并加载后端。没有运行 Docker、Flutter、真实推送或修改任何证书；本次未重跑生产 HTTPS 用例，生产验收历史见下文。

| 检查 | 本次实际结果 |
| --- | --- |
| Maven 打包 / JUnit | `dev-up.sh` 中打包成功，原 4 项测试通过，无失败、无错误 |
| Vue 类型检查 / 生产构建 | `npm --prefix admin run build` 通过 |
| 原有真实 HTTP 回归 | `python3 scripts/verify-mvp.py`，21 组通过；前缀 `vf5f577d8`，完成时间 `2026-11-11T17:05:30.597587+08:00` |
| 新增真实 HTTP 验收 | `python3 scripts/verify-announcements.py`，5 组通过；前缀 `msg67591207` |
| Playwright Chromium | `npm --prefix admin run test:e2e`，4 项通过，原有生产 HTTPS 专用用例按规则跳过 1 项 |
| 页面检查 | 检查公告管理回执、员工抄送详情及 390px 窄屏；消息分类按钮换行，新增页面无横向溢出断言 |
| 提交范围 | 只提交任务相关源码、迁移、测试及文档，工具、数据、上传文件、截图、trace 不提交 |

新增 HTTP 覆盖：仅管理员发布；全员及指定部门（含下级、不含其他部门）；排除停用账号；发布后新员工和调部门不改变快照；标题/范围/重复附件/他人附件校验；多附件鉴权及未登录、非接收人拒绝；打开自动已读、显式已读及首次时间幂等；管理员已读未读人数和姓名/工号/发布时部门；普通人不能看回执、撤回或管理列表；撤回隐藏详情、已读入口和附件，管理员回执保留、发布撤回有审计。四类申请均可抄送，报销通过管理员代提交；拒绝不存在、停用或申请人本人抄送；抄送人可看单据和附件但不能审批、撤回或登记支付；提交和终审均通知；五类消息、自身数据隔离、当前轮待办及汇总、通知旧接口兼容及首次已读、非法类别、分页、未登录和越权均有真实断言。

新增浏览器覆盖：管理员中文页面发布部门公告、上传两份 PDF、核对两名未读；代员工提交外出并选择抄送；员工登录消息中心、打开公告和下载附件、核对未读下降，查看抄送详情及只读按钮、标记站内通知、切换五类消息；管理员核对已读/未读变化并撤回，员工列表不再显示公告。原组织账号、首次改密、出差审批/打卡、报销明细/多票据/支付更正及财务入口用例全部保留。

续跑修复：申请类型选择框加明确可访问名称，修复原 `getByLabel('申请类型', {exact:true})` 等待失败；补齐代提交表单的抄送多选。通过 trace 定位退出后立即导航导致响应被中断、残留失效令牌的测试竞态，改为等待登录页再继续。接口验收与浏览器按顺序执行，避免同时发布全员公告影响未读数断言；控制台错误断言保留。

限制：仅站内投递，不连接 APNs/FCM/厂商通道，也不伪造推送；员工提交仍使用 API，网页提供消息及审批处理、管理员代提交。公告纯文本，无评论、定时、审批；打开或标记即记已读，不证明实际阅读。消息手动刷新；各类每页 200 条，汇总不限于该页。最多 30 份公告附件、每份 10MB，抄送最多 100 项；附件不做病毒/真实性识别。撤回保留管理员历史及回执，附件的其他独立授权保持有效。没有清空验收历史。

运行证据：`.runtime/announcement-verification.json`、`.runtime/verification-result.json`、`.runtime/announce-admin.png`、`.runtime/messages-employee.png`、`.runtime/messages-mobile.png`、`backend/target/surefire-reports/`；开发服务保持运行，可直接演示。

## 报销增量验收（2026-11-11）

在一期代码上新增 Flyway V2，使用原有本机 PostgreSQL / Redis 和上传目录启动，历史数据保留。通过 `bash scripts/dev-up.sh` 加载新后端；本次未运行 Docker、未重复生产 HTTPS 验收，下文生产验收为一期历史记录。开发服务继续运行，可直接演示。

| 检查 | 本次实际结果 |
| --- | --- |
| Maven JUnit / 打包 | 原有 4 项单元测试通过，打包成功 |
| Vue 类型检查 / 生产构建 | 通过 |
| `python3 scripts/verify-mvp.py` | 21 组真实接口场景全部通过；前缀 `v00ecff84`，员工 ID 46，出勤记录 ID 22；完成时间 `2026-11-11T16:06:30.520168+08:00` |
| `npm --prefix admin run test:e2e` | 3 个 Chromium 用例通过，1 个生产环境专用用例按原规则跳过；原有测试保留 |
| 页面人工核对 | 核对 `.runtime/admin-expense.png`，中文明细、两张票据、待支付状态、操作人及更正原因均展示 |
| 提交检查 | `git diff --check` 通过；运行数据、上传文件、工具及浏览器产物不纳入提交 |

新增真实接口覆盖：无明细、明细总额不符、金额超出两位小数、无票据、重复票据、他人票据提交拒绝；两行明细和两张票据提交及详情展示；审批人可鉴权下载、无关员工下载拒绝；未批准支付拒绝；最终批准自动进入待支付；普通员工、主管及无关员工支付操作返回 403；财务登记已支付，记录操作人和时间；重复登记拒绝；更正回待支付必须说明原因；管理员更正保留完整历史与审计；已批准报销不可撤销；未参与低额审批的财务可查询、下载及登记已批准报销。

新增浏览器覆盖：管理员代提交报销，页面显示合计不符及无票据错误，增加第二行明细、多选上传两张 PDF；详情展示明细和鉴权下载按钮；批准后待支付、登记已支付、填写原因改回待支付、展示历史、隐藏撤销按钮。财务账号仅显示审批单据入口，可查看申请人、下载票据、标记已支付并显示财务操作人。

原有回归：组织/账号、首次改密、审批跳级/越权拒绝、半天和年度额度、两级分流、管理员代批、撤回/撤销、并发余额扣减、附件权限、出差/外出、正常及出勤打卡、停用、CSV 和审计仍通过。首次浏览器回归发现共用表单对非报销提交了空白明细，已将非报销的 `items/attachmentIds` 清空后重跑，完整套件通过。

限制：支付仅人工登记，不打款；票据不做税票真实性识别、跨单据查重或病毒扫描；新报销最多 100 行、30 张票据，每张 10MB；已提交报销不可编辑。历史总额映射为一行、原附件映射为一张，历史缺失票据保留并提示；已批准历史报销初始化待支付，需人工核对，不补造支付时间或操作人。

## 以下为一期原验收记录

验收日期：2026-11-11，业务时区 Asia/Shanghai。最后一次真实接口验收完成时间：`2026-11-11T15:32:52.389688+08:00`。

## 实际环境与启动

本机没有 Docker Engine，按任务允许的备用方式运行：仓库内 Temurin JDK 21.0.13、Maven 3.9.9、PostgreSQL 17.11、Redis 8.0.2，系统 Node.js 24.22.1。工具安装在 `.tools`，运行数据及日志在 `.runtime`；没有注册系统服务。Spring Boot 3.5.7，Vue 3 + Vite 7 + TypeScript。

实际执行 `bash scripts/install-local.sh`、`bash scripts/dev-up.sh`；另执行 `bash scripts/dev-down.sh` 后再次启动，确认停止脚本能停止本项目服务、重新启动能保留数据。运行中后端使用 `.runtime/oa-app.jar` 独立副本，避免再次打包覆盖运行文件。

验收结束保留开发服务运行：

- 管理后台 `http://127.0.0.1:5173`：HTTP 200。
- 后端 `http://127.0.0.1:8080/api/health`：`{"status":"正常"}`。
- PostgreSQL `127.0.0.1:55432`，Redis `127.0.0.1:56379`，都只监听回环地址。
- 开发管理员 `admin`；空库初始密码 `Admin@2026Dev`。首次验收已强制修改为 `Admin@2026Verified`，当前实例使用新密码。密码仅用于本机开发。

## 自动化结果

| 检查 | 真实执行结果 |
| --- | --- |
| Maven 编译、打包、JUnit | 4 项测试，0 失败、0 错误 |
| Vue 类型检查及生产构建 | `vue-tsc --noEmit && vite build` 通过 |
| HTTP 业务验收 | 19 组全部通过，真实 PostgreSQL + Redis，无内存数据库替代 |
| 开发环境 Playwright Chromium | 2 个用例通过；正式环境用例由本套件跳过后单独运行 |
| 生产环境 Playwright Chromium | 1 个 HTTPS 登录与权限展示用例通过 |
| Nginx | 实际配置检查、HTTPS 启动、静态构建、API 反代均通过 |
| Docker Compose | v2.40.0 `config --quiet` 通过；没有 Docker 守护进程，因此未实际构建/启动容器 |
| 脚本语法 | shell `bash -n`、Python 编译检查通过 |

实际执行的主要命令：

```bash
source scripts/env.sh
mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" test
npm --prefix admin run build
python3 scripts/verify-mvp.py
npm --prefix admin run test:e2e
bash scripts/verify-production.sh
.tools/docker-compose --env-file .env.example config --quiet
```

## 真实 HTTP 场景

最后一次运行使用随机账号前缀 `v984d10b1`，验收员工编号 `35`，有效出差期间生成的真实出勤记录编号 `16`。脚本保留测试历史，不清库。

1. 管理员可使用已修改的密码登录。
2. 未登录访问被拒绝；CORS 允许登记来源并拒绝其他来源。
3. 开通账号、部门树、角色、直属上级及手机号登录；拒绝部门循环和员工管理越权。
4. 跨用户查询、代提交、越权审批和跳级审批被拒绝。
5. 超过一天两级审批，最终批准才扣余额，重复审批被拒绝，未开始撤销返还余额并通知已批人。
6. 半天申请只审批一次并扣减 0.5 天。
7. 事假不扣余额；驳回须意见；待审批可在一级通过后撤回。
8. 法定假强制证明、附件按权限下载、已批天数计入年度上限。
9. 管理员代批后的撤销通知同时送达实际处理人和原分配审批人。
10. 真实并发审批共享余额：只成功一次，不会扣成负数。
11. 直属上级已是部门负责人或部门未指定负责人时，只审批一次。
12. 后台配置阈值即时生效；报销超过阈值流转财务并批准，不涉及打款。
13. 无定位/无精度/非法定位/非公司网络拒绝；不能伪造出口 IP；登记 SSID 或出口 IP 可正常打卡。
14. 未来批准不能提前出勤；当前有效出差可在任意网络出勤；已开始不能撤销；真实/模拟打卡均可查询。
15. 批准请假期间仍可正常打卡和查记录；请假不构成出勤授权。
16. 连续五次错误后账号登录限流生效。
17. 退出立即销毁 Redis 会话。
18. 停用立即阻断既有会话和新登录，历史审批与打卡保留。
19. CSV 导出含 UTF-8 BOM；管理操作审计有记录；非管理员不能导出全员数据。

另有编码路径和路径参数回归：员工访问 `/api/%61dmin/users`、`/api/admin;test=1/users` 均 403，无会话访问编码路径为 401。鉴权绑定实际 MVC 控制器，管理员控制器使用 `@AdminOnly`，避免仅检查原始 URL 前缀而被绕过。

并发测试同时批准两个各 0.5 天的申请，共享仅 0.5 天余额：实际得到一个 200、一个 400，最终余额 0，没有负数或重复扣减。

首次空库验收执行过管理员强制改密和旧会话 401；后续每次 HTTP 验收都创建首次登录员工并改密，浏览器单独创建新管理员验证首次改密前看不到管理菜单，改密后重新登录才能进入工作台。

## 浏览器实际操作

Chromium 通过中文页面完成：管理员登录 → 新增部门 → 开通账号、选择主部门和直属上级 → 登记 Wi-Fi → 代员工提交出差 → 查看审批链、填写意见、批准 → 导出 CSV → 模拟正常打卡 → 查询该员工记录 → 打开假期类型、余额、分流、通知和审计 → 退出。用例断言页面无未处理的 JavaScript 错误。

第二个用例创建新管理员，验证首次改密界面、旧密码修改、重新登录及退出。

## 生产 profile 和 HTTPS

`verify-production.sh` 临时运行 `prod` 后端 8081 和仓库内 Nginx 1.26.3，在 `127.0.0.1:8443` 提供实际 HTTPS，使用与 Compose 相同的 `deploy/nginx/oa.conf`，仅替换本机端口、后端地址和文件路径。验证后自动关闭这两个临时进程，保留开发实例。

- Python 使用本机证书作为 CA 验证 HTTPS 证书及主机名，首页和 API 反代正常，存在 HSTS 头。
- 生产 `/auth/me` 返回 `development=false`。
- 管理员已登录请求 `/api/admin/dev/punch` 返回 **404**。
- Chromium 在 HTTPS 页面实际登录，工作台可见、“正式环境”标识可见、模拟打卡菜单不存在，退出正常。浏览器仅为本机自签名证书启用了测试用忽略证书警告；Python 证书验证未关闭。
- Compose 只映射 443，PostgreSQL、Redis、后端没有主机端口映射；配置校验通过，但本机无 Docker Engine，不能将配置校验当成容器启动成功。

## 数据与证据

验收结束时数据库包含 42 个开发/验收账号、60 张审批单和 19 条打卡（其中 8 条标记为模拟）。SQL 检查所有账号密码字段均为 BCrypt 格式，余额最小值为 0，未出现负数。账号数量包含保留的多次验收数据，不是预置生产数据。

可在当前工作区查看以下被 git 忽略的运行证据：

- `.runtime/verification-result.json`：最后一次 19 组结果、时间和测试对象编号。
- `.runtime/production-smoke.json`：HTTPS、证书验证、生产 profile 与模拟接口 404。
- `.runtime/admin-overview.png`、`.runtime/admin-request-form.png`、`.runtime/admin-audit.png`、`.runtime/admin-production.png`：实际浏览器截图。
- `.runtime/backend.log`、`.runtime/admin.log`、`.runtime/postgres.log`、`.runtime/redis.log`、`.runtime/production.log`、`.runtime/nginx/`：运行日志。
- `backend/target/surefire-reports/`：JUnit 报告。浏览器失败时 `admin/test-results/` 保存截图和 trace。

上述证据包含开发测试数据，不提交数据库、工具、会话、证书私钥或上传证明文件。接口定义、启动步骤、时间口径、SSID/定位信任边界和已知限制详见 [README](../README.md) 与 [API 文档](api.md)。

## Flutter 员工端续跑验收（2026-11-11）

在提交 `a677ddd` 上继续保留并完成已有改动。Flutter 3.47.9 stable / Dart 3.13.7，Android SDK 位于 `/home/box/Android`，Java 沿用仓库21版本；未安装 SDK 到 git，未改历史迁移。V4 新增职务、工作电话及设备会话表；管理员页面支持维护字段和踢设备。

| 本次检查 | 结果 |
| --- | --- |
| Maven JUnit | 4 项通过，编译成功 |
| Flutter 静态分析 | 无问题 |
| Dart 测试（含真实服务） | 9 项通过、无跳过，专用普通员工完成登录、消息汇总、通讯录及退出 |
| 员工新增接口 `verify-mobile.py` | 通过，覆盖资料白名单/工作电话、姓名工号部门搜索、部门下级、停用隐藏、长度校验、设备隔离/本人及管理员踢设备、当前设备退出及改密失效 |
| 既有业务 `verify-mvp.py` | 21 组通过，含报销、定位网络、审批并发及权限回归 |
| 公告消息 `verify-announcements.py` | 5 组通过 |
| Vue 类型检查与生产构建 | 通过 |
| Playwright Chromium（1440×1000） | 5 项通过、1 项生产 HTTPS 用例按环境跳过；另对最终设备弹窗改动定向复验 |
| Android debug 构建 | 成功，`apksigner` 验证 v2 签名通过，包名 `com.company.oa_employee`、最低 Android API 24、target API 36 |
| Android 实机/模拟器安装运行 | 未执行；`adb devices` 无连接设备 |
| iOS 编译/签名/IPA | 未执行；Linux 无 Xcode，无 IPA |

首次改密测试原失败原因：带中文的 Mock HTTP 响应未声明 UTF-8，测试构造响应时抛出编码异常，被 API 封装转换为网络错误。修正模拟响应字符集后，完整验证首次登录进入强制改密页、禁止返回、没有工作台入口、提交密码后清除 token 并回到登录页。没有放宽首次改密规则或删测试。

管理后台定向操作：登录 → 账号与角色 → 编辑职务/工作电话 → 保存并核对列表 → 登录设备 → 查看设备 → 退出此设备 → 接口验证原会话401，页面无未捕获 JS 异常。Browser 插件不可用，使用仓库已有 Playwright；截图 `.runtime/flutter-admin-devices.png`。过程中批量验收曾触发本机登录 IP 频率限制，只清除开发 Redis 的 `login-ip:127.0.0.1` 计数后重跑，未降低业务限流规则。浏览器套件应逐次运行，避免并发写入同一 `admin/test-results` 目录。

实际执行主要命令：

```bash
bash scripts/dev-up.sh
source scripts/env.sh
mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" test
python3 scripts/verify-mobile.py
python3 scripts/verify-mvp.py
python3 scripts/verify-announcements.py
npm --prefix admin run build
npm --prefix admin run test:e2e
npm --prefix admin run test:e2e -- tests/mobile-support.spec.ts
/home/box/flutter/bin/flutter analyze mobile
# 在 mobile 中用已改密的专用员工环境变量运行（不提供账号时 live 用例会跳过）
OA_TEST_LOGIN=测试工号 OA_TEST_PASSWORD=测试密码 /home/box/flutter/bin/flutter test
# 在 mobile 中，设置仓库 JDK 和 ANDROID_HOME 后
/home/box/flutter/bin/flutter build apk --debug
```

可安装调试包保存在 `/workspace/oa-system/.runtime/oa-employee-debug.apk`（约159 MiB，未提交）；SHA-256：`9fcbaac58a9656bd249075067f9147e6961422b2ff3162267f78ff6e230cae4e`。仓库根目录用 `/home/box/Android/platform-tools/adb install -r .runtime/oa-employee-debug.apk` 安装。构建与签名验证通过不能代替实机验证。

地址配置入口为登录页「服务地址与设备」。默认127.0.0.1:8080，安卓模拟器改10.0.2.2，USB真机可 adb reverse，局域网真机用电脑IP且后端需监听该网卡；日后配置受信任 HTTPS 服务地址。具体命令见 [mobile/README.md](../mobile/README.md)。iOS 在 Mac 使用同一工程 `mobile/ios/Runner.xcworkspace`，仍需依赖解析、编译、签名及真机联调；全员安装还需适用的苹果企业账号或 MDM 条件，本次未办理。

尚未连接真机验证定位授权、SSID、系统文件选择/查看、安全存储，也未实测 iOS；消息采用页面刷新，无真实推送、离线提交、后台定位或员工网页。现有数据库、上传及本机服务继续保留，构建物和日志均忽略不入库。证据：`.runtime/flutter-tests-live.log`、`flutter-mobile-http.log`、`flutter-mvp-resume.log`、`flutter-announcements-resume.log`、`flutter-e2e-resume.log`、`flutter-devices-e2e-final.log`、`flutter-maven-final.log`、`flutter-analyze-final.log`、`flutter-apk-build-resume.log`。
