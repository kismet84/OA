# 一期 MVP 本机验收记录

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
