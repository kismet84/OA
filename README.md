# 公司内部 OA · 一期 MVP

Java 21 + Spring Boot 3 单体后端，PostgreSQL 保存业务，Redis 保存会话及登录失败计数，本地目录保存证明文件；Vue 3 + Vite + TypeScript 中文管理后台。仅做管理员界面，员工及审批人通过 API 操作；没有员工办公网页或移动客户端。

## 已实现

- 工号或手机号登录、BCrypt 密码、首次强制改密、改密后全部旧会话失效、退出、停用立即生效。角色：员工、直属上级、部门负责人、管理员、财务/行政。
- 部门树、主部门、直属上级、部门负责人、账号开通/编辑/重置密码/停用；组织循环检查和关联角色检查；保留历史数据。
- 管理员维护 Wi-Fi SSID 和办公网出口 IP，配置假期及年度上限、年假/调休剩余余额、审批分流阈值。
- 请假、出差、外出、报销提交与审批。支持半天，审批链去重、一级/二级分流、通过/驳回、撤回、未开始撤销和站内通知。报销也已支持后台代提交与财务审批，不自动打款。
- 年假、调休最终批准扣余额，撤销返还；事假、病假不扣余额、支持年度上限；法定假须证明。审批链及规则快照，数据库事务和行锁防止重复扣减、并发超扣。
- 正常打卡和出勤 API 均强制定位；正常打卡校验公司网络；出勤校验本人有效出差/外出批准时段。仅记录事实，不计算迟到、早退、缺卡，不做补卡和加班。
- 管理后台查询审批、打卡，分页与筛选、CSV 导出、证明上传/鉴权下载、站内通知、管理审计；开发环境有模拟打卡页，生产环境不提供模拟接口。

## 仓库结构

```text
backend/                  Spring Boot、Flyway SQL 迁移、JUnit 测试、Dockerfile
admin/                    Vue 3 + TypeScript 管理后台、Playwright 浏览器测试、Dockerfile
deploy/nginx/oa.conf      HTTPS 反代和静态页面配置
docker-compose.yml        PostgreSQL + Redis + 后端 + 管理后台 Nginx
scripts/                  仓库内依赖安装、本机启停、真实接口与生产 HTTPS 验收
docs/api.md               权限边界、请求格式、时间规则与接口索引
docs/mvp-verification.md  本机实测记录
.tools/ .runtime/         本机工具、数据库、上传文件、日志和测试截图（不提交）
```

## 方式一：本机运行（本次实际采用）

本机没有 Docker，已使用仓库内 JDK 21.0.13、Maven 3.9.9、PostgreSQL 17.11、Redis 8.0.2 和系统 Node.js 实际启动。以下命令在仓库根目录执行：

```bash
cd /workspace/oa-system
bash scripts/install-local.sh   # 已安装可略过；下载只写入本仓库 .tools
bash scripts/dev-up.sh
```

自动安装脚本适用于 **Debian 13 x86_64**，需要系统已具备 Node.js 22.12+（或 20.19+）、npm、Python 3、curl、tar、dpkg-deb、ripgrep、openssl，以及 Debian 13 常用运行库。当前环境这些基础工具均已存在；脚本提取 PostgreSQL/Redis 的 deb 到 `.tools/system`，不会注册系统服务，也不需要 root。其他系统可先安装上述基础工具和 JDK 21、Maven 3.9、PostgreSQL 17、Redis 7+，再按下文手动启动；不要直接在不兼容发行版使用脚本中的 Debian 二进制。

`dev-up.sh` 初始化数据库、启动 PostgreSQL/Redis，执行后端测试与打包、前端依赖安装/类型检查/构建，然后启动后端和 Vite。重复执行保留数据，并在构建成功后重启后端加载新代码。所有开发服务仅监听回环地址：

| 服务 | 本机地址 |
| --- | --- |
| 管理后台 | http://127.0.0.1:5173 |
| 后端健康检查 | http://127.0.0.1:8080/api/health |
| PostgreSQL | 127.0.0.1:55432，数据库/用户 `oa`，仅开发密码 `oa-local-only` |
| Redis | 127.0.0.1:56379，开发回环实例无密码 |

**空数据库的预置管理员：`admin` / `Admin@2026Dev`，首次登录必须改密。仅限开发环境。**

本次验收脚本已将本机管理员密码改为 **`Admin@2026Verified`**，所以当前已运行实例请用这个密码登录。重新创建空数据库时仍使用初始密码。验收产生的测试部门、账号、审批和打卡保留供核查，不影响新建业务；它们以“验收”或“浏览器验收”命名。

```bash
# 停止本项目本机服务，保留数据库和上传文件
bash scripts/dev-down.sh
# 日志
cat .runtime/backend.log
cat .runtime/admin.log
```

若使用已自行安装的 PostgreSQL/Redis，可在已有实例创建 `oa` 用户和 `oa` 数据库，通过环境变量指定连接，分别启动：

```bash
source scripts/env.sh
mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" package
# 将以下变量设为自己的数据库和 Redis 连接，生产必须使用独立强密码
export DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/oa
export DATABASE_USER=oa
export DATABASE_PASSWORD=自己的数据库密码
export REDIS_HOST=127.0.0.1
export REDIS_PORT=6379
export REDIS_PASSWORD=自己的Redis密码
export UPLOAD_DIR="$PWD/.runtime/uploads"
SPRING_PROFILES_ACTIVE=dev java -jar backend/target/oa-1.0.0.jar
# 另一个终端在仓库根目录执行
source scripts/env.sh
npm --prefix admin ci
npm --prefix admin run dev
```

## 方式二：Docker Compose + HTTPS

需要 Docker Engine 及 Compose v2。若机器尚未安装，可按 Docker 官方对应发行版安装说明安装；本仓库附带的本机脚本不改系统目录，不安装 Docker 守护进程。本次机器无 Docker，容器启动未实际执行；已用本机生产 profile + 真正 Nginx HTTPS 验证同一份反代配置，见验收记录。

```bash
cd /workspace/oa-system
bash scripts/prepare-compose.sh
# 查看并记录 .env 中自动生成的 BOOTSTRAP_PASSWORD；不要提交 .env
# 本机生成的自签名证书只用于 https://localhost 验证，浏览器会提示证书不受信任
# 或：cp .env.example .env 后自行填写三个独立强密码与 HTTPS 域名

docker compose config --quiet
docker compose up -d --build
docker compose logs -f backend
```

访问 `https://localhost`，空数据库账号为 `admin`，初始密码取 `.env` 的 `BOOTSTRAP_PASSWORD`，首次必须修改。生产 profile 不使用源码中的开发默认管理员密码。

公网部署时：将 `.env` 的 `CORS_ORIGINS` 改成实际的 `https://oa.公司域名`；将正规 TLS 证书、私钥分别放到 `.runtime/certs/fullchain.pem`、`.runtime/certs/privkey.pem`，配置证书续期；可把 Nginx `server_name _` 改为实际域名。Compose **只映射 443**，后端、数据库、Redis 不映射宿主机端口；防火墙仅开放 443。不要把 Vite 或 `dev` profile 暴露公网。

Nginx 固定内网地址 `172.30.0.10`，后端仅信任该来源的 `X-OA-Client-IP`，Nginx 会覆盖外部同名头。若内网段冲突，请同步修改 Compose 子网、Nginx 固定地址和 `TRUSTED_PROXIES`。数据库和 Redis 分别用 `.env` 中的密码，证明文件存在 `uploads` 卷；持久化卷包括 `postgres-data`、`redis-data`、`uploads`。`docker compose down` 保留卷；日常停止不要使用 `down -v`。

建议备份 PostgreSQL 与上传文件卷，保留 `.env` 和 TLS 私钥的安全副本；修改 `.env` 中数据库密码不会自动更改已有数据库的用户密码。

## 手工验证操作

1. 登录管理员，首次改密并重新登录。在“部门组织”建立部门，在“账号与角色”开通负责人（部门负责人角色）、主管（直属上级角色）、员工；编辑部门选择负责人，编辑员工选择主部门和主管。每人一个主部门，审批直接读取组织关系。上级/负责人均未指定或指向自己时拒绝提交，不会自动批准。
2. 在“公司网络”登记实际 SSID 或办公出口 IP。在“假期余额”给员工录入当年年假/调休**当前剩余天数**；“假期配置”调整事假/病假年度上限、法定假可休天数。“审批分流”默认请假、出差/外出 1 天，报销 1000 元，可直接修改。
3. 在“审批单据”管理员代员工提交请假。半天选择 09:00–13:00 或 13:00–18:00；一天为 09:00–18:00。超过阈值需要两级；主管已是部门负责人或未设部门负责人则一次。管理员可在详情中处理当前步骤（禁止自批）；员工和主管使用 API 验证各自权限，详见 [API 文档](docs/api.md)。病假建议附证明，预置法定假必须上传 PDF/PNG/JPEG。
4. 通过后查看余额扣减；尚未开始可撤销并返还，已批人收到站内通知；驳回必须写意见；待批可撤回。事假、病假只核年度上限，不扣手工余额。
5. 提交并批准覆盖当前时刻的出差/外出。在开发“模拟打卡”页选择员工，填写类型、定位、精度、SSID、出口 IP：正常打卡要求登记网络，出勤要求处于批准时段。缺定位、无有效批准或非公司网络均应拒绝。出差/外出每天仍需打出勤卡，无自动生成记录。
6. “打卡记录”“审批单据”按员工、类型、状态/日期筛选和翻页，导出 CSV；“操作审计”核查管理操作。停用账号后新登录与已有会话均不可继续，历史记录仍可由管理员查询。

**时间口径：**统一 `Asia/Shanghai`；申请按自然日计天，含周末和节假日，上午下午各半天，跨年需拆单。出差/外出也使用半天边界，跨日申请会覆盖中间夜间；打卡有效期为 `[开始时间,结束时间)`。本期没有节假日工作日历。法定假预置天数为演示配置，需管理员按公司所在地和内部规则调整。

## 自动化验证

先启动本机服务，再运行：

```bash
source scripts/env.sh
# 四项 JUnit 测试：半天计数、非法时段、CSV 公式转义、严格 IP 校验
mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" test
# 真实 PostgreSQL + Redis + HTTP，包含并发扣减、鉴权、停用、打卡和报销
python3 scripts/verify-mvp.py
# 仅首次需要下载浏览器，存放于本仓库 .tools/browsers
(cd admin && npx playwright install chromium)
# 中文后台浏览器全流程、首次改密；正式环境用例此处跳过
npm --prefix admin run test:e2e
# Debian 13 本机：临时启动 prod 后端 8081、Nginx HTTPS 8443，结束自动停止
bash scripts/verify-production.sh
```

接口验收可在已有数据上重复执行，使用随机工号，不清库。它会修改开发管理员初始密码、生成测试数据并调整验收员工余额，不要在正式数据库运行。若管理员已改为其他密码，设 `OA_ADMIN_PASSWORD`；接口地址可设 `OA_API`，前端测试地址可设 `OA_WEB`。单 IP 15 分钟最多 100 次登录尝试，短时间大量重跑可能命中限流，届时等待窗口到期。

结果写在 `.runtime/verification-result.json`、`.runtime/production-smoke.json`，页面截图在 `.runtime/admin-*.png`；失败浏览器用例的截图/trace 在 `admin/test-results`。实测摘要见 [验收记录](docs/mvp-verification.md)。

## 安全与实现说明

- 密码 BCrypt 12 轮，不返回/导出密码哈希；最多 72 UTF-8 字节。连续 5 次登录失败后锁定该登录标识 15 分钟，另设连接来源 IP 尝试上限。
- Redis 保存随机 Bearer 会话；服务端每次请求读取账号启用状态、角色及会话版本。密码重置、改密、账号变更使旧会话失效。前端仅在当前浏览器会话中存令牌，不使用长期 localStorage。
- 管理接口按管理员鉴权；普通用户仅看本人打卡/余额，审批单按申请人或实际审批链授权，附件继承关联单据访问范围。审批检查当前步骤，禁止跳批和自批。
- 管理操作、代提交、审批、模拟打卡、登录/改密/退出和 CSV 导出写数据库审计日志，不记密码或令牌。审批/撤销和余额更新在同一事务，相关用户/单据行锁防止超扣与重复处理。
- CORS 仅允许明确配置的本机/HTTPS 来源，无通配；正式接口忽略客户端自行填写的出口 IP。文件按 UUID 命名且只能鉴权下载，禁止静态公开上传目录。
- SSID/经纬度在 API 中是设备声明，服务端只能校验格式、网络登记和授权时段，无法证明定位未被伪造；若需可靠内网校验，请只登记公司出口 IP。后续正式 App 再加入可信的网络、定位采集和设备控制。

## 已知限制与后续

一期没有员工网页或 Flutter App，员工改密、提交和审批可用 API，当前后台的代提交与模拟页用于管理/开发。通知仅站内查询，无短信、邮件或推送；无审批转办界面，负责人离职前需先处理/撤回在途单据并调整组织关系。账号停用保留数据，不提供硬删除账号或已关联部门。

余额由管理员录入当前剩余值，后续应加入正式调账流水和年假结转规则；自然日半天模型尚未覆盖各地工作日历、弹性时段和复杂法定假规则。附件只做类型/大小限制，暂不做病毒扫描或未关联附件自动清理。列表默认 200 条分页，审计/通知分别取最新 500/200 条；导出上限 10000 条。当前未实现组织/账号的批量导入、消息推送或第三方集成。

建议下一步依次完善**报销**（费用明细、票据、财务复核与支付状态，仍不自动打款）、**公告和消息**，再开发 **Flutter App**，接入真实定位、网络采集和员工审批体验。
