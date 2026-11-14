# 二期员工办公网页验收

历史验收：基线 `aab20c0`，验收日期 2026-11-11。以下测试数字和限制仅适用于该日期/基线，不是当前提交的生产或真机验收。网页打卡及后续模块已取代当时“不提供现场打卡/无日程任务文档”的结论。

当前能力（2026-11-13，整改基线 33369c9 后）：员工网页已有正常/外勤打卡入口，需安全上下文和定位权限，网络与出差/外出授权由服务端校验；已支持日程、任务、文档与月度考勤（计算迟到、早退、缺卡）。网页消息仍手动刷新、没有浏览器推送，Android FCM 是独立通道。

本次增量复用现有 Vue 工程、Spring Boot API 和 HTTPS 配置，无业务接口、数据库迁移或规则变更。

## 实现与入口

- `/staff/` 为员工独立 HTML / JS 入口，`/` 保留管理后台；一次 `npm --prefix admin run build` 输出 `admin/dist/index.html` 与 `admin/dist/staff/index.html`。开发沿用 Vite `/api` 代理，HTTPS 沿用现有 Nginx 静态资源及 API 反代。
- 员工用工号或手机号登录，首次强制改密，改密使所有旧会话失效。登录设备默认名「员工办公浏览器」、平台 Web；用户可改设备名称。`oa-staff-token` 仅存 sessionStorage，与后台会话分开；8小时、停用、远程退出由原后端校验。
- 六类申请均使用原 `/requests`；报销含明细、多票据及鉴权下载，假期证明、抄送和审批记录可查看。本人按规则撤回/撤销，审批人处理当前步骤，抄送只读。消息复用原分类、汇总及已读状态，处理后刷新当前分类而不跳回默认分类。
- 公告打开或标记已读回执、附件下载；通讯录组织层级/搜索/分页；个人资料、工作电话、改密、余额与流水、单台/其他设备退出；打卡记录日期/类型查询及补卡来源。
- 所有业务时间为北京时间。已批准单据的撤销按钮正确处理后端带时区时间及兼容无偏移的上海时间，在非中国浏览器时区也保持一致；最终判断由服务端完成。

## 执行结果

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 后端单元测试 | `mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" test` | 8项通过，无失败/跳过 |
| 双入口类型检查及构建 | `npm --prefix admin run build` | 通过 |
| 原后台及新增员工浏览器回归 | `npm --prefix admin run test:e2e` | 8项通过；1项HTTPS专用用例按原约定跳过，随后在HTTPS通过 |
| 二期考勤接口 | `python3 scripts/verify-phase2-attend.py` | 10组通过 |
| 一期业务接口 | `python3 scripts/verify-mvp.py` | 21组通过 |
| 公告消息接口 | `python3 scripts/verify-announcements.py` | 5组通过 |
| 员工资料及设备接口 | `python3 scripts/verify-mobile.py` | 通过 |
| Flutter静态分析与现有测试 | 在 `mobile` 执行 `flutter analyze --no-pub`、`flutter test --no-pub` | 分析无问题，16项测试全部通过（含2项真实接口测试，无跳过） |
| 真实HTTPS及生产profile | `bash scripts/verify-production.sh` | 原生产静态资源/证书校验/API反代/登录/模拟接口404验证通过，3项浏览器用例全部通过 |

Browser plugin not available：本会话未提供 Browser 插件，使用仓库原有 Playwright / Chromium。员工流程为 `/staff/` → 首次登录/改密 → 手机号登录 → 六类申请提交 → 本人撤回或审批人批准 → 未开始加班撤销 → 公告打开并由管理接口核对已读回执 → 通讯录/打卡记录/资料/设备管理。另验证远程退出后清除令牌并回到登录；同一标签页切换管理入口不复用员工令牌。

六类申请均实调后端；报销验证两行明细、两张PDF票据及鉴权下载；审批由另一个浏览器上下文中的直属上级完成；抄送分类和只读入口可查看；公告在真实部门接收快照中核对 `read_at`。测试使用动态未来年度、专用随机工号及独立部门，仅设置专用员工余额，不更改全局审批配置。桌面1440px和手机390px均核对内容和截图，无横向页面溢出、框架报错或员工完整流程控制台错误；测试浏览器使用 America/Los_Angeles 时区。

连续运行全部接口和多轮浏览器调试曾触发开发回环IP的100次/15分钟登录限流。仅清理该开发实例 Redis 的 `login-ip:127.0.0.1` 临时计数后，完整浏览器套件与HTTPS验收顺序通过；未修改限流逻辑或配置，未清空Redis/业务数据。验收账号、单据、公告和审计按既有约定保留。

移动端回归使用本次浏览器验收建立的专用员工/上级账号，经环境变量提供给既有真实接口测试。为避免工具缓存写到仓库外，将已有Flutter SDK复制至忽略目录 `.tools/flutter-web-regression`，分析与测试使用 `--no-pub`，配置目录及PUB_CACHE均设在仓库内；未改动Flutter源码、依赖锁文件或生成APK。

日志及截图只写入 `.runtime`：`web-backend-tests.log`、`web-build.log`、`web-e2e.log`、`web-https.log`、`web-*-http.log`、`web-flutter-analyze.log`、`web-flutter-tests.log`、`staff-desktop.png`、`staff-mobile.png`；失败调试证据由 Playwright 管理，不提交。

## 默认值与限制

使用管理员既有员工账号，无新预置业务账号；默认补卡期限3个自然日、加班比例1:1、加班分流阈值1天均沿用服务端配置，表单提示从 `/attendance-policy` 读取。业务规则、额度及附件归属最终由后端核验，未在网页另算。列表每页最多200条、余额流水最新500条，网页消息手动刷新，无浏览器推送；Android FCM 见当前 README。

历史基线不提供网页打卡及日程/任务/文档；这些限制已被后续实现取代，当前能力见开头。报销支付登记仍在管理后台。只验收Chromium电脑和窄屏浏览器，未连接实体手机或验证Safari；原生文件、日期和多选控件随系统/浏览器语言变化。沿用本机已有证书，不办理苹果账号、不部署外部服务器/域名、不生成APK。
