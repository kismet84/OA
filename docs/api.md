# OA API 约定（含二期加班与补卡）

所有接口在 `/api` 下。除 `POST /auth/login`、`GET /health` 外均需要 `Authorization: Bearer <token>`。开发地址 `http://127.0.0.1:8080/api`，正式环境通过 HTTPS 反代同源访问。JSON 输入使用 camelCase，查询结果保留数据库 snake_case；日期使用上海时区。错误返回 `{"message":"中文原因"}`，400 参数/业务规则，401 未登录/失效，403 无权，404 不存在，409 数据冲突，429 登录限流。

## 身份与权限

- `POST /auth/login`：`{"login":"工号或手机号","password":"密码","deviceName":"员工手机","platform":"Android"}` → `token`、`user`。会话有效期 8 小时，不滑动续期。`deviceName` / `platform` 可选，最多100 / 30字；未填显示「未命名设备」/「未知平台」，兼容既有客户端。
- 登录限流采用 Redis 固定 15 分钟窗口：真实客户端 IP 仅累计失败，100 次后拒绝；成功不消耗或清空同出口 IP 的失败额度。只在 remoteAddr 属于 `oa.trusted-proxies`（`TRUSTED_PROXIES`）时读取代理覆盖的 `X-OA-Client-IP`，校验并规范化；不直接信任 `X-Forwarded-For` / `CF-Connecting-IP`，非受信来源忽略所有转发头。受信代理缺失/非法专用头时记录告警，登录使用独立 `login-ip:proxy-unknown` 桶（1000 次失败/15 分钟），不直接返回400；打卡仍严格返回400。
- 登录名先 trim 并转小写；工号按大小写不敏感匹配。登录名 `login:<规范名>` 和命中用户的 `login-user:<用户ID>` 均累计失败，任一达到5次后（含正确密码）返回429，工号/手机号、切换 IP 不能绕过。未知账号同样按规范名累计，401错误提示统一。窗口自首次失败起计、不因后续失败延长；到期解除，成功清除该用户ID与工号/手机号失败键，IP失败键保留。
- `GET /auth/me` → `user: {id,name,roles,mustChange,version}`、`development`。
- `POST /auth/password`：`{"oldPassword":"原密码","newPassword":"至少10位新密码"}`。密码最多 72 个 UTF-8 字节，修改后所有旧会话失效，须重新登录。
- `POST /auth/logout`：销毁当前会话。
- 首次登录只允许查自身、改密码及退出。普通请求权限包括本人发起、审批链被指派或实际处理（approver_id / actor_id）、改派前后审批人、抄送人；审批人可查看相关单据；抄送人可查看抄送单据及附件，公告接收人可查看未撤回公告；财务额外可查看全部已批准报销及其票据，供人工支付登记；管理员可查询全员、代提交和处理审批，但不可自批。
- 角色枚举：`EMPLOYEE` 员工、`SUPERVISOR` 直属上级、`DEPT_HEAD` 部门负责人、`ADMIN` 管理员、`FINANCE` 财务/行政。

## 管理员维护

以下路径均为 `/admin/...`，全部要求管理员；写操作记录审计，不提供硬删除员工接口。

| 路径 | 方法 | 输入 / 作用 |
| --- | --- | --- |
| `/departments` | GET / POST | 列表 / `{name,parentId?,headId?}` |
| `/departments/{id}` | PUT | 同上；拒绝部门循环，负责人须有效且具有负责人角色 |
| `/users` | GET / POST | 列表 / `{employeeNo,phone?,name,jobTitle?,workPhone?,roles:[],departmentId,managerId?,enabled,password}` |
| `/users/{id}` | PUT | 同上；`password:null` 保留原密码，非空则重置并强制改密；修改后旧会话失效 |
| `/networks` | GET / POST | 列表 / `{kind:"SSID"或"IP",value}`；IP 必须是完整地址，不接受 CIDR |
| `/networks/{id}` | DELETE | 移除网络登记 |
| `/leave-types` | GET / POST | `{code,name,deductBalance,annualLimit?,proofRequired,enabled}` |
| `/leave-types/{id}` | PUT | 同上；已有 code 不可修改，预置法定假要求证明和可休天数 |
| `/balances` | GET / PUT | 查询 / `{userId,typeId,year,amount}`，设置**当前剩余**余额，而非增加额度 |
| `/workflow` | GET / PUT | `{leaveThreshold,travelThreshold,expenseThreshold,financeId?,correctionDays?,overtimeRatio?}`；GET 返回 snake_case |
| `/audit` | GET | 最近 500 条审计 |

停用前须调整该人员的在职下属、负责部门、财务配置和待审批链；防止产生无人可批的单据。已有申请可由管理员处理或撤回后调整。至少保留一个有效管理员，不能停用自身或移除自身管理员角色。

## 申请、审批和证明

- `GET /leave-types`：启用的假期类型；`GET /balances`：本人余额。
- `POST /attachments`：multipart 字段 `file`，最多 10MB，仅 PDF/PNG/JPEG，返回 `id`。附件以随机 UUID 保存在本地目录，下载须鉴权。
- `GET /attachments/{uuid}`：作者、关联申请人、审批链内人员、抄送人、未撤回公告的接收人、已批准报销的财务或管理员可下载，不开放静态上传目录。
- `POST /requests`：提交示例：

```json
{
  "kind": "LEAVE",
  "typeId": 1,
  "startAt": "2026-11-20T09:00:00",
  "endAt": "2026-11-20T13:00:00",
  "reason": "个人安排",
  "attachmentId": null
}
```

`kind`：`LEAVE` 请假、`TRAVEL` 出差、`OUTING` 外出、`EXPENSE` 报销、`OVERTIME` 加班、`CORRECTION` 补卡。管理员可以另传 `userId` 代提交，员工不可代他人。报销传 `amount`、`items` 和 `attachmentIds`（见下方），起止时间和 typeId 留空。请假必须传 typeId；证明必须由当前提交人上传（管理员代提交时使用管理员上传的证明）。

天数由服务端计算，不能手填：起点 09:00/13:00，终点 13:00/18:00，上午和下午各半天，按自然日计含周末；请假、出差和外出跨年必须拆单，加班允许跨年并计入开始年度。出差可用跨日时段覆盖夜间。请假、出差和外出结束时间须在未来，允许起始已到达的申请；仍需审批通过才能出勤。请假、出差和外出保留原有互斥规则；加班与请假、加班互斥，与出差/外出可重叠。年假/调休余额以开始年度为准，最终批准才扣减；年度上限只统计已批准且未撤销的假期。

- `GET /requests?status=&kind=&userId=&inbox=false&offset=0`：每页最多 200 条。普通用户仅能看到本人、分配给其审批或抄送给其的单据。`inbox=true` 查当前轮到本人处理的待办。
- `GET /requests/{id}`：单据与 `steps` 审批链、审批意见及时间；报销同时返回 `items`、`attachments`、`payment_history` 和支付状态字段。
- `POST /requests/{id}/decision`：`{"approve":true,"comment":"同意"}`，驳回传 false 且意见必填。仅当前步骤审批人或管理员可处理；禁止自批。重复处理会拒绝。审批链和假期扣减/年度上限规则在提交时快照。
- `POST /requests/{id}/cancel`：申请人或管理员撤回待批单，或撤销尚未开始的已批单。后者返还余额并向已批人生成站内通知；已开始及已批报销不可撤销。
- `GET /notifications`：本人最新 200 条；`POST /notifications/{id}/read`：本人标记已读。

## 报销明细、票据与支付登记

先逐张调用 `POST /attachments`，再提交：

```json
{
  "kind": "EXPENSE",
  "amount": 1200.00,
  "reason": "客户拜访费用",
  "items": [
    {"description": "交通", "amount": 700.00},
    {"description": "住宿", "amount": 500.00}
  ],
  "attachmentIds": ["上传返回的UUID一", "上传返回的UUID二"]
}
```

- 明细 1–100 行，`description` 为必填用途或类别，最多 200 字；总额及每行金额为 0.01–99999999 元，最多两位小数。服务端以十进制定点金额精确求和，不相等返回 400，不自动修正总额。
- 票据 1–30 张，不可重复；每张最多 10MB，PDF/PNG/JPEG，必须是当前提交人上传且文件仍存在。不存在、他人上传或仅传旧字段 `attachmentId` 均不能替代报销必填的 `attachmentIds`。管理员代提交时使用管理员上传的票据。非报销沿用单张 `attachmentId`。
- 总额**超过**后台 `expenseThreshold`（默认 1000 元）才追加配置的财务/行政审批人，等于阈值仍一级审批；原有去重、禁止自批规则保持。
- `GET /requests/{id}` 返回 `items: [{line_no,description,amount}]`、`attachments: [{id,original_name,media_type}]`，按录入顺序排列；附件仍经 `/attachments/{id}` 鉴权下载。
- 审批 `status` 继续使用 `PENDING/APPROVED/REJECTED/...`，支付状态独立存储于 `payment_status`：未批准为 null，最终批准后为 `UNPAID`（待支付），人工登记后为 `PAID`（已支付）。不调用银行或支付渠道，不自动打款，已批准报销仍不可撤销。
- `POST /requests/{id}/payment`：财务或管理员提交 `{"status":"PAID","reason":"可选说明"}`；改回待支付使用 `{"status":"UNPAID","reason":"必填的更正原因"}`。仅已批准报销可操作，重复状态及空白更正原因返回 400；其他角色返回 403。使用单据行锁及事务，状态、历史和审计一起提交。
- 单据上的 `payment_actor_id`、`payment_acted_at` 记录最近一次人工登记操作人和上海时间；详情的 `payment_history` 按时间顺序保留全部登记，包含 `status/actor_id/actor_name/acted_at/reason`。审计操作为 `MARK_EXPENSE_PAID` / `RESET_EXPENSE_UNPAID`，`target` 是单据 ID，可与历史中的原因关联核对。首次批准进入待支付沿用批准审计。
- 财务可查询全部已批准报销（包括未经过财务二级审批的低额报销）及其票据，不能因此查看无关的未批准报销或其他类型单据；普通请求权限（本人、审批链指派/实际处理人、改派前后审批人、抄送人）保持，详情、列表和月度报销导出共用。后台财务账号显示审批单据和消息中心入口，无组织维护或普通管理员全员导出权限；可调用月度报销导出，范围为全部已批准报销加普通权限内其他报销，状态筛选只在可见范围内生效。

升级采用 Flyway V2 增量迁移：历史报销原总额和理由映射为一行明细，原附件映射为一张票据；历史已批准报销初始化为待支付，不补造支付人或支付时间。历史无票据单据保持可查看并提示，不追溯拒绝原有在途审批。新报销必须满足上述明细和票据规则。票据只校验文件类型、大小、归属和存在性，不做税票真实性识别或跨单据查重，也没有编辑已提交报销的接口。

## 二期：补卡、加班与余额流水

继续复用 `/requests`、`/requests/{id}`、`/decision`、`/cancel` 和原有权限。两类均必填 `reason`（最多2000字），可带 `ccUserIds`，管理员可带 `userId` 代提；待批可撤回，驳回必须填写意见。提交、最终通过/驳回向抄送人发送关联单号的站内通知；进入待我审批、我发起的、抄送我的和系统通知分类，不另建消息规则。

补卡示例（字符串表示上海本地时间，不传时区偏移）：

```json
{
  "kind": "CORRECTION",
  "startAt": "2026-11-10T10:21:00",
  "punchKind": "NORMAL",
  "reason": "忘记打卡",
  "ccUserIds": [5]
}
```

- `punchKind` 为 `NORMAL` 正常打卡或 `FIELD` 出勤；`endAt/typeId/amount` 不使用。不得为未来，默认上海当前日期不得晚于补卡日期加3天，即该日期结束后的3个自然日内；后台 `correctionDays` 可设0–365（0表示只允许当天）。期限只限制提交，审批时不重新计算期限。
- 同人、同时间、同类型已有打卡记录或待批/已批补卡不能再提；撤回和驳回后可重提。提交与最终批准都核验重复和出勤资格；出勤须存在本人 `APPROVED` 且 `start_at <= 补卡时间 < end_at` 的出差/外出。请假不阻止补卡。并发操作由事务锁及活动补卡唯一索引保护。
- 正常补卡不要求历史 SSID/IP，补卡也不要求当前定位。当前版本不采集参考位置。最终批准插入 `punches`，`punched_at` 为申请时间，`correction_request_id` 为单号；定位、精度、SSID、出口IP为空，不能冒充现场记录。历史及新现场记录此标记为空，现场接口仍强制定位、网络/出勤资格。
- 直属上级一次审批；未指定则部门负责人；都无则按ID选择首个启用、非本人的管理员。仍禁止自批，若没有有效审批人则提示配置。批准补卡不可撤销。

加班示例：

```json
{
  "kind": "OVERTIME",
  "startAt": "2026-11-20T09:00:00",
  "endAt": "2026-11-20T13:00:00",
  "reason": "项目支持",
  "ccUserIds": [5]
}
```

- 起止半天规则与请假相同，必须结束晚于开始，最长366天，允许过去时段及跨年。服务端计算 `days`，不接收手填天数。与本人待批/已批未撤销的请假、加班时段按半开区间检查重叠；相邻允许，出差/外出允许。
- 沿用 `leaveThreshold`（默认1天）分流，超过阈值追加负责人；直属上级已是负责人或未配置负责人时仅一次，禁止自批及审批链去重保持。
- 提交时保存 `overtime_ratio`、`credited_days = days × 比例`；最终批准按开始年度增加预置 `COMP` 调休余额（即便尚无该年余额也自动创建）。中间审批、驳回和撤回不入账。
- 批准后仅尚未开始可撤销，退回快照额度并通知已批人；若当前调休不足则400且事务回滚，单据仍批准，不产生负数或部分流水。已开始不可撤销。无加班费、调休过期或结转处理。

| 接口 | 作用 |
| --- | --- |
| `GET /attendance-policy` | 登录员工读取 `{correction_days,overtime_ratio,leave_threshold}`，供表单提示 |
| `GET/PUT /admin/workflow` | 原字段增加 `correctionDays`（0–365）和 `overtimeRatio`（0.01–100，最多两位小数），默认3和1；可选以兼容旧客户端，省略保持原值 |
| `GET /balances` | 原本人余额接口，已包含加班入账后的调休 |
| `GET /balance-history` | 本人最新500条余额流水，含 `type_name/year/delta/balance_after/request_id/actor_id/reason/created_at` |
| `GET /admin/balance-history?userId=` | 管理员最新500条，可按员工过滤，增加员工、假期、操作人中文姓名 |

流水原因：`OVERTIME_APPROVED` 加班计入、`OVERTIME_CANCELLED` 加班撤销、`LEAVE_APPROVED` 扣假、`LEAVE_CANCELLED` 返还、`ADMIN_SET` 管理员设置。管理员设置仍为当前剩余额度，差额计流水；调休允许四位小数，其他假期仍半天整数倍。全部余额变更在同一数据库事务内锁申请人、余额行，单据、金额和流水一起提交，重复批准不会重复入账。

后台「审批分流」可编辑新配置；「审批单据」支持六类筛选、代提、抄送、审批和撤销；「打卡记录」展示补卡来源及单号；「余额流水」核对变动。原 CSV 导出支持 `kind=OVERTIME|CORRECTION`，增加补卡类型、兑换比例/天数和补卡单号中文列。Flyway V5 只扩展字段、余额精度及新表，不修改历史业务值；历史现场标记为空、历史余额不补造流水。

## 打卡

`POST /punches`：

```json
{
  "kind": "NORMAL",
  "latitude": 31.2304,
  "longitude": 121.4737,
  "accuracy": 12,
  "ssid": "公司办公网"
}
```

定位三项必须存在且合法：纬度 [-90,90]、经度 [-180,180]、精度 (0,100000] 米；现场打卡时间取服务端当前上海时间；补卡通过审批申请，见上文。`NORMAL` 要求匹配登记 SSID 或真实来源 IP。`FIELD` 要求本人有已批准的 TRAVEL/OUTING，且 `startAt <= 当前时间 < endAt`；任何地点和网络均可。请假不阻止打卡和查记录。

正式接口不接受用户填写出口 IP，仅使用连接来源；若连接来源恰好在 `TRUSTED_PROXIES` 中，则读取由反向代理**覆盖写入**的 `X-OA-Client-IP`。不要信任任意来源的转发头。Nginx 样例使用连接来源覆盖此头。Mac 的 Caddy + Cloudflare 链路必须在后端启动环境设置 `TRUSTED_PROXIES=127.0.0.1`，并在 Caddy 的 `reverse_proxy` 中设置 `header_up X-OA-Client-IP {http.request.header.CF-Connecting-IP}`（[完整样例](../deploy/caddy/README.md)）。Caddy/后端只绑定 loopback，Cloudflare 隧道是外部唯一入口；无 `CF-Connecting-IP` 时不填造 IP，受信代理缺少合法专用头的打卡请求返回 400「可信反向代理必须传递真实客户端 IP」。后端不读取任意 `X-Forwarded-For`，本机 dev 默认不信任 loopback。SSID 是客户端声明，不能证明设备真实连入指定 Wi-Fi；需要强网络校验时只登记办公出口 IP，后续 App 再补充可信网络采集。

`GET /punches?userId=&from=2026-11-01&to=2026-11-30&kind=&offset=0`：日期范围包含首尾，200 条/页。员工只能看本人，部门负责人无权批量看他人打卡，管理员可看全员。

仅 `dev` profile 提供 `POST /admin/dev/punch`，后台模拟页调用：

```json
{
  "userId": 3,
  "exitIp": "203.0.113.10",
  "punch": {"kind":"NORMAL","latitude":31.23,"longitude":121.47,"accuracy":20,"ssid":"公司办公网"}
}
```

模拟仍执行全部业务校验，记录 `simulated=true` 和管理审计。生产 `prod` profile 无此控制器，也不展示模拟页面。

## 导出

管理员可用 `GET /admin/exports/requests.csv?userId=&status=&kind=` 和 `GET /admin/exports/punches.csv?userId=&from=&to=&kind=`。最多 10000 条，超限须缩小范围；UTF-8 BOM、标准 CSV 引号转义，并对可能被表格软件解释为公式的文本加前导单引号。导出有审计记录。

## 公告与回执

Flyway V3 新增公告、接收人快照、公告附件及申请抄送关联表；不改写历史审批或通知。

| 路径 | 方法 | 作用 |
| --- | --- | --- |
| `/announcements` | POST | 仅管理员发布 `{title,body,departmentId?,attachmentIds?}`，返回 `{id}` |
| `/announcements?management=false&offset=0` | GET | 本人接收且未撤回的公告；管理员传 `management=true` 可查全部和已读/未读人数 |
| `/announcements/{id}` | GET | 正文及 `attachments:[{id,original_name}]`；接收人打开即记录首次 `read_at` |
| `/announcements/{id}/read` | POST | 接收人标记已读，重复操作不覆盖首次时间 |
| `/announcements/{id}/receipts` | GET | 仅管理员，返回 `{readCount,unreadCount,read:[],unread:[]}` |
| `/announcements/{id}/withdraw` | POST | 仅管理员撤回，重复撤回不重复记审计 |

标题必填且最多 200 字，正文必填且最多 50000 字，纯文本展示。`departmentId` 省略或 null 为全员，否则指定部门及其全部下级。发布与组织变更共用事务锁，快照仅包含当时启用员工，保存 `user_id/name/employee_no/department_name`；之后人员变动不增删、不改名。回执名单包含这些快照字段及 `read_at`，管理员即使撤回后仍可查询。管理员查看详情时，若自己在接收名单且公告未撤回，也记已读。

附件先通过原 `/attachments` 上传，`attachmentIds` 最多 30 个且不重复，必须归当前发布管理员所有；类型 PDF/PNG/JPEG、单份最多 10MB。下载沿用 `GET /attachments/{uuid}`，公告接收人仅在未撤回时可下载，非接收人返回 403；原作者/管理员和关联申请的独立授权继续有效。非管理员非接收人不能看正文或回执；撤回后普通接收人正文、已读和公告附件访问均拒绝。发布/撤回审计为 `PUBLISH_ANNOUNCEMENT` / `WITHDRAW_ANNOUNCEMENT`，target 为公告 ID。无定时发布、评论或公告审批。

## 申请抄送与消息中心

`POST /requests` 六类申请均可额外传 `ccUserIds:[员工ID,...]`，管理员代提交相同。最多 100 项，省略/null/空数组表示不抄送；服务端去重，拒绝不存在、停用或申请人本人的 ID。`GET /colleagues` 返回当前启用且非本人的同事 `[{id,name,employee_no,department_name}]`，供选人使用。`GET /requests/{id}` 增加 `cc:[{id,name,employee_no,department_name}]`。历史单据为空名单。

抄送人可查询单据列表、详情及关联证明/票据；抄送身份本身不给审批、撤回/撤销或支付权限，所有写操作仍按原规则鉴权。已有审批人/财务/管理员角色的独立授权保持。提交和最终通过/驳回各产生一条抄送系统通知，含 `request_id` 可定位详情；中间审批不发送终审通知。

- `GET /messages?category=pending|initiated|cc|notifications|announcements&offset=0`：分别为当前轮到本人审批、本人申请、抄送本人、本人系统通知、本人未撤回公告；类别必填，无效类别 400。每页最多 200 条，按 ID 倒序，offset 负值按 0 处理。申请类沿用 `/requests` 字段；通知含 `id/message/created_at/read_at/request_id`（旧通知的 request_id 可为空）；公告含 `id/title/published_at/read_at`。
- `GET /messages/summary` → `{pending:当前待办数,notifications:未读系统通知数,announcements:未读且未撤回公告数}`。三个字段各自为数字，统计全部记录，不受 200 条分页限制，可复用于后续 App 红点；网页不自动推送或轮询；安卓系统通知推送见下文。
- 原 `GET /notifications` 继续返回本人最新 200 条；`POST /notifications/{id}/read` 保留首次已读时间，无权或不存在返回 403。消息中心使用同一张表，状态同步。

所有新接口均需登录及完成首次改密；停用账号原有会话立即失效。业务时间仍为 Asia/Shanghai。安卓设备登记和 FCM 见下文；未配置凭证也可完整查询、阅读和下载站内消息。


## 员工资料、组织通讯录与设备会话（Flutter 一期）

除管理员设备接口外，以下均面向已完成首次改密的登录用户，不依赖员工角色字符串；只能操作本人资料与会话。管理员维护账号时可填 `jobTitle`（最多100字）和 `workPhone`（最多40字，仅数字、空格、`+()-`）；`phone` 仍是登录手机号，独立保存，不自动复制到工作电话。旧客户端不传这两个可选字段时保存为空。

| 路径 | 方法 | 输入 / 返回 |
| --- | --- | --- |
| `/profile` | GET | 本人 `{id,employee_no,name,roles,department_id,department_name,job_title,work_phone}` |
| `/profile/work-phone` | PUT | `{workPhone:"021-12345678"}`；null/空串清空，不允许修改工号、角色、部门。额外 JSON 字段不参与更新 |
| `/directory/departments` | GET | 组织树的平铺节点 `[{id,name,parent_id}]`，客户端按 parent_id 展示层级 |
| `/directory?departmentId=&search=&offset=0` | GET | departmentId 可不传；包含所选部门及下级。在职同事按姓名/工号/部门模糊搜索（最多100字，`%`、`_` 按字面搜索），按工号排序，每页最多200人 |
| `/auth/sessions` | GET | 本人有效设备 `[{id,device_name,platform,created_at,expires_at,current}]` |
| `/auth/sessions/{id}` | DELETE | 退出本人某台设备，传 `others` 退出除当前设备外的全部设备 |
| `/admin/users/{userId}/sessions` | GET | 管理员查看指定员工有效设备，同上 |
| `/admin/users/{userId}/sessions/{id}` | DELETE | 管理员退出指定员工设备；`others` 同样表示排除调用者当前设备 |

通讯录只返回 `{id,name,employee_no,department_id,department_name,job_title,work_phone}`，不返回登录手机号、密码、角色或其他敏感字段；停用账号立即隐藏。登录手机号只有被另行登记为工作电话时才出现在通讯录。

设备 id 是随机登录 token 的 SHA-256 标识，不是可用于认证的 bearer token。有效会话同时检查 Redis、数据库到期/撤销状态、账号启用状态及 session_version。当前标识相对于请求者，管理员查看别人的设备时均为 false。退出单台不影响其他会话，越权退出他人设备返回404；非管理员调用管理接口403；退出当前设备后后续请求401；改密或管理员编辑账号后全部旧设备会话失效。登录/退出/撤销设备/改工作电话均有审计。设备名称和平台属于客户端声明信息，不代表硬件认证。

迁移 `V4__employee_profile_and_sessions.sql` 仅新增字段和会话表，旧员工资料字段为空；升级前仅存在 Redis 的会话需重新登录登记设备。撤销和过期会话留在表中供审计关联，本期未实现定期清理任务。安卓 App 支持 FCM，消息仍可通过现有消息接口刷新。


## 二期员工网页接入

员工网页入口 `/staff/`（开发 Vite 与 HTTPS 静态资源构建均支持），管理入口 `/` 不变。网页与 Flutter 使用本文相同接口及权限，本次未新增或改变业务 API、数据库迁移或默认审批规则。员工网页登录携带 `platform: "Web"` 和可编辑 `deviceName`，令牌存当前浏览器会话 `oa-staff-token`；固定8小时及设备退出均由原会话接口执行。首次改密前不调用业务接口。

网页使用 `/messages` 分类查询、`/requests` 自助提交/详情/审批/撤回、`/announcements` 回执、`/directory`、`/profile`、`/balances`、`/balance-history`、`/attendance-policy` 和 `/auth/sessions`。上传及鉴权下载沿用 `/attachments`。网页不调用 `POST /punches`；只通过 `GET /punches` 查看本人记录。财务支付登记继续使用原管理后台。

## 二期日程、会议室与参会通知

增量迁移 `V6__calendar_and_meetings.sql` 新增会议室、会议、参会回复，向现有 `notifications` 增加可空 `meeting_id`，不改写原有审批、账号、打卡或旧通知。全部接口沿用登录、首次改密和8小时设备会话。下表路径均以 `/api` 为前缀。

| 接口 | 方法 | 参数与返回 |
| --- | --- | --- |
| `/meeting-rooms?management=false&offset=0` | GET | 默认只列启用会议室，`management=true` 仅管理员可用，含停用；按 id 排序，每页最多200条 |
| `/meeting-rooms` | POST | 管理员新增 `{name,location?,capacity?,enabled:true}`；返回 `{id}` |
| `/meeting-rooms/{id}` | PUT | 管理员修改，完整提交同上字段；`enabled:false` 停用 |
| `/meeting-rooms/{id}` | DELETE | 管理员操作：无任何会议记录物理删除；有记录（含已取消）则停用，返回中文 `message` |
| `/meetings?day=2031-02-03&mine=true&offset=0` | GET | 按上海自然日查询；默认 `mine=false`，`includeCancelled=false`。可传 `roomId`、`organizerId` 筛选，按开始时间、id 排序，每页最多200条 |
| `/meetings?from=2031-02-03T00:00:00&to=2031-02-04T00:00:00` | GET | 半开时间范围查询（`start_at < to AND end_at > from`），允许跨日；`day` 优先。不传范围默认上海当天。`to` 必须晚于 `from` |
| `/meetings/{id}` | GET | 会议详情；所有已登录员工可查看占用摘要，说明和完整名单仅组织者、参会人和管理员可见 |
| `/meetings` | POST | 在职登录者创建，组织者自动为本人；输入见下例。返回 `{id,warnings:[中文提示]}` |
| `/meetings/{id}` | PUT | 组织者或管理员修改，完整提交输入；组织者不变；返回 `{id,warnings}` |
| `/meetings/{id}/cancel` | POST | 组织者或管理员软取消，立即释放会议室并通知参会人；已取消不可修改或再次取消 |
| `/meetings/{id}/reply` | POST | 受邀参会人提交 `{response:"ACCEPTED"}` 或 `{response:"DECLINED",reason:"已有安排"}`；不可代回复，组织者无需回复自己，已取消不可回复 |

创建及修改示例（无时区偏移的本地时间，均解释为 **Asia/Shanghai**，响应时间同样不带偏移，客户端不可再按设备时区转换）：

```json
{
  "title": "项目评审",
  "startAt": "2031-02-03T09:00:00",
  "endAt": "2031-02-03T10:00:00",
  "roomId": 1,
  "description": "请准备评审材料",
  "attendeeIds": [12, 13]
}
```

- 标题必填，最多200字；说明最多2000字。会议室、说明和参会人可空；`roomId:null` 表示仅建日程。最多邀请200人，自动去重并移除组织者；组织者始终视为参会人。受邀人必须为在职账号。
- 开始、结束必填，结束严格晚于开始；允许过去开始、跨日和跨年，不受审批半天规则限制。不做循环会议、视频会议或外部日历同步。
- 会议室名称必填（最多100字），地点可选（最多200字），容量可空或正整数（最大999999999）；名称去除首尾空格后，在启用会议室中不区分大小写唯一。停用后历史会议可查看、可只改标题/说明/名单，但不能新订或改期到该会议室；可改到其他启用会议室或不订会议室。
- 会议室占用即时生效，**没有审批链**。同室未取消会议以 `startAt < 其他.endAt AND endAt > 其他.startAt` 判冲突，端点相邻允许；冲突返回409，中文原因含冲突标题及会议编号。所有日程写操作使用 PostgreSQL 事务级 advisory lock 串行校验并写入，覆盖并发首次预定、改期、取消和会议室停用/删除。
- 个人日程冲突、人数超容量只返回 `warnings`，不阻断。三个客户端保存成功后展示提示。已取消默认隐藏，可通过 `includeCancelled=true` 查询，详情保留。
- 列表仅包含占用摘要：`id,title,start_at,end_at,room_id,room_name,organizer_id,organizer_name,cancelled_at`，不泄露说明或名单。详情返回 `canEdit`、`canReply`；有权者另见 `description`、`attendees`。每位参会人包含 `user_id,name,employee_no`；组织者/管理员可见全部 `response,reason,responded_at`，普通参会人只见自己的回复及其他人的姓名工号。
- 回复默认 `PENDING`（待回复），`ACCEPTED`（接受）、`DECLINED`（请假）；请假原因可空，最多300字。改开始/结束时间或会议室时重置保留参会人的回复和原因，再发通知；只改标题/说明不重置。新加入的人默认待回复并收到邀请；移出的人失去参会权限并收到移出通知。
- 创建邀请、改期/换会议室、取消、新增/移出参会人均复用系统通知。组织者不重复收到参会邀请；通知含可空 `meeting_id`，旧 `request_id` 和旧通知兼容。Flutter、后台和 `/staff/` 消息中心可打开关联会议，安卓 FCM 统一覆盖此类系统通知；无 APNs/厂商推送。
- 审计动作：`SAVE_MEETING_ROOM`、`DISABLE_MEETING_ROOM`、`DELETE_MEETING_ROOM`、`CREATE_MEETING`、`UPDATE_MEETING`、`CANCEL_MEETING`、`REPLY_MEETING`。未登录401；越权403；输入错误400；会议室占用或启用名称冲突409；不存在404，错误为中文。

验收：`python3 scripts/verify-phase2-calendar.py`（真实 PostgreSQL/Redis，含并发与权限）、`npm --prefix admin run test:e2e -- calendar.spec.ts`、`cd mobile && flutter test`。Flutter 真实接口用例额外提供 `OA_TEST_LOGIN`、`OA_TEST_PASSWORD`、`OA_TEST_MANAGER`（同密码的独立受邀测试账号）和 `OA_TEST_CALENDAR_ROOM_ID`；只使用专用账号/会议室，测试保留审计并取消自己的会议。

## 二期待办任务（指派与跟进）

Flyway `V7__assigned_tasks.sql` 增量建立 `tasks`、当前负责人 `task_assignees`、不可编辑删除的 `task_followups`；原 `notifications` 增加可空 `task_id`，旧 `request_id`、`meeting_id` 及历史数据保留。复用登录、在职账号、组织通讯录、系统通知与审计，不进入审批链。以下路径均以 `/api` 为前缀，均须登录并完成首次改密。

| 接口 | 方法 | 输入与返回 |
| --- | --- | --- |
| `/tasks?scope=assigned&status=TODO&dueFrom=2031-02-01&dueTo=2031-02-28&creatorId=12&assigneeId=13&offset=0` | GET | 各筛选可选；scope 空为有权查看的任务，`assigned` 为我负责的，`initiated` 为我发起且未取消的。管理员空 scope 可查全部。截止起止含整个上海自然日，无截止的任务不匹配日期筛选。按创建时间、id 倒序，每页最多200条，offset 翻页；汇总不受此上限限制 |
| `/tasks/{id}` | GET | 完整任务及 `creator_name,overdue,canEdit,canRestore,assignees,followups`；负责人含 `user_id,name,employee_no`，跟进含 `id,user_id,user_name,content,created_at`，按时间、id 正序 |
| `/tasks` | POST | `{title,description?,dueAt?,assigneeIds:[12,13]}`，发起人自动为登录者（管理员也为本人）；返回 `{id}`，初始状态 `TODO` |
| `/tasks/{id}` | PUT | 发起人或管理员完整提交同上字段；发起人不变，返回 `{id}`；负责人变动通知加入和移出的人 |
| `/tasks/{id}/followups` | POST | 发起人、现任负责人或管理员追加 `{content:"已联系客户"}`；必填纯文本，最多5000字，不可修改删除；返回中文 message |
| `/tasks/{id}/status` | POST | `{status:"IN_PROGRESS",content?:"开始处理"}`；可在状态变更事务中同时追加跟进，完成时建议填写但不强制；返回中文 message |

- 标题必填且最多200字，说明可空且最多5000字；负责人至少1人，去重后最多200人，须为在职账号，可包含本人。重复指派不重复通知，创建且指派给自己保留一条通知。
- `dueAt` 可空或空串；支持 `YYYY-MM-DD`（当天23:59:59）或无偏移 ISO 日期时间，例如 `2031-02-03T18:00:00`，均解释为 **Asia/Shanghai**；返回时间不带偏移，客户端不可按设备时区再转换。截止仅筛选和提示，已过期的未结束任务返回 `overdue:true`，不自动改状态。
- 状态为 `TODO` 待处理、`IN_PROGRESS` 进行中、`COMPLETED` 已完成、`CANCELLED` 已取消。发起人、负责人或管理员可在未结束时切换待处理/进行中、标记完成；状态变更也通知其他参与人。
- 仅发起人或管理员可修改内容/名单或软取消。已完成仍可补充跟进、改标题/说明，但不能改负责人或截止；须由发起人或管理员重开到 `IN_PROGRESS` 后再改。已取消禁止编辑、跟进和改状态，仅管理员可纠错恢复到 `TODO`；恢复/重开通知负责人，历史跟进保留。
- 发起人、当前负责人、管理员可查看完整任务，其他人详情和写操作403；列表始终按权限收窄，即使传他人筛选也不会扩大权限。移出的负责人立即离开自己的待办，丧失该任务阅读权限（仍为发起人或管理员的除外），原跟进不删除。移出通知保留，但点击详情仍按当前权限判断。
- 创建指派、改名单、修改、跟进、状态切换、完成、取消、恢复使用既有系统通知；除创建时的本人指派通知，操作者不重复收到通知，同一接收人去重。审计动作：`CREATE_TASK,UPDATE_TASK,FOLLOW_TASK,STATUS_TASK,COMPLETE_TASK,CANCEL_TASK,RESTORE_TASK`。写操作在事务中锁任务行，避免并发修改、取消与跟进相互穿透。
- 未登录401，越权403，参数/状态错误400，不存在404；错误信息中文。任务没有附件、子任务、循环任务、项目看板或甘特图；通过统一系统通知接入安卓 FCM，不做新工作台汇总，文档资料为下文独立模块。

### 合并到现有消息待办

`GET /messages?category=pending&offset=0` 保留现有审批分类，返回当前轮到本人审批的单据与本人负责的 `TODO/IN_PROGRESS` 任务，按创建时间、id 倒序混排，每页最多200条。同时间同id用条目类型稳定排序。`GET /messages/summary` 的 `pending` 是两类全部记录总数，不受分页上限影响；任务完成、取消或移出名单立即退出列表与统计。

审批条目新增 `item_type:"REQUEST"`，保留原单据字段；任务条目 `item_type:"TASK",task_id`，带任务字段、`creator_name,overdue`，不要把其 id 当成审批单号；两类 id 可相同。客户端分别调用 `/requests/{id}` 与 `/tasks/{task_id}`。其他审批分类仍保留，通过 `/tasks?scope=initiated` 查看自己发起且未取消的任务。系统通知返回可空 `task_id`，三端可据此打开任务详情，旧单据与会议通知不受影响。

验收：`python3 scripts/verify-phase2-todo.py`（真实 PostgreSQL/Redis，含201项全量计数/分页与审批共存）、`npm --prefix admin run test:e2e -- tasks.spec.ts`、`cd mobile && flutter analyze && flutter test`。管理员测试密码用 `OA_ADMIN_PASSWORD` 覆盖；浏览器测试使用隔离账号并走真实登录、创建指派、双类别详情、跟进完成及中文管理员恢复流程。测试数据保留历史和审计，测试输出与截图不提交。

## 二期文档资料（制度与文件下发）

Flyway `V8__company_documents.sql` 新增公司文档、当前附件绑定、历史文件保护及首次预览回执；既有 `notifications` 仅增加可空 `document_id`。旧审批、公告、会议、任务及通知保持兼容。以下路径以 `/api` 为前缀，全部须登录并完成首次改密，业务日期为 **Asia/Shanghai**。

| 接口 | 方法 | 输入与返回 |
| --- | --- | --- |
| `/documents` | POST | 仅管理员创建并立即发布，完整输入见下例；返回 `{id}` |
| `/documents/{id}` | PUT | 仅管理员完整修改同一组字段；已下架文档修改后仍下架；返回 `{id}` |
| `/documents/{id}/withdraw` | POST | 仅管理员软下架，保留文件、回执及审计；重复调用不重复审计 |
| `/documents/{id}/republish` | POST | 仅管理员重新上架已下架文档，再次通知当前接收人并更新发布时间；重复调用不重复通知 |
| `/documents?category=POLICY&keyword=制度&publishedFrom=2026-11-01&publishedTo=2026-11-30&offset=0&limit=200` | GET | 筛选均可选；按分类、标题关键字（不区分大小写、按字面匹配）、发布时间的上海自然日（含起止日）查询；时间、id 倒序。offset≥0，limit为1–200，默认200。员工只见当前已发布且在接收范围内的文档；管理员 `management=true` 包含下架文档 |
| `/documents/{id}` | GET | 返回当前字段及 `attachments:[{id,original_name,media_type}]`，**不记已读**；范围外或下架的普通员工403 |
| `/documents/{id}/attachments/{uuid}/preview` | GET | 返回 `{pages,watermark}`，PDF总页数、图片为1页；不返回原件、不记已读 |
| `/documents/{id}/attachments/{uuid}/pages/{page}` | GET | 1起始页码；返回服务端烧录水印的 `image/png`，成功生成后插入首次已读回执，重复打开不覆盖时间 |
| `/documents/{id}/attachments/{uuid}/download` | GET | 允许下载时返回水印文件：PDF重绘全部页面、图片统一导出PNG；禁止下载时403，不能通过预览或通用附件接口拿到原件 |
| `/documents/{id}/receipts` | GET | 仅管理员；`{readCount,unreadCount,read,unread,historical}`。统计和前两名单按当前接收范围，历史已读但已移出/停用人员保留在 `historical`；人员包含 `user_id,name,employee_no,department_name,read_at` |

```json
{
  "title": "员工考勤制度",
  "description": "请全体研发人员查阅",
  "category": "POLICY",
  "departmentId": 12,
  "attachmentIds": ["00000000-0000-0000-0000-000000000001"],
  "allowDownload": true
}
```

- 标题必填，去首尾空格、最多200字；说明可空、最多5000字；分类必填且仅 `POLICY` 公司制度、`FILE` 通知文件、`OTHER` 其他；`departmentId:null` 为全员，否则指定一个部门及其全部下级，接收人为当前在职账号。范围形状与公告相同，文档按当前组织及范围实时授权；公告仍沿用原有快照规则。管理员可查看全部文档。
- 状态 `PUBLISHED` 已发布、`WITHDRAWN` 已下架。无草稿、定时、物理删除、版本树。员工始终看到当前内容；修改范围和组织后立即按当前范围授权，旧预览URL每次请求均重新校验，旧附件不可通过当前文档路由查阅。管理员下架后仍可查详情、回执、预览及取回原件。
- `allowDownload` 缺省或null为true。附件为1–10份、不重复，须由**当前操作管理员本人**经既有 `POST /attachments` 上传、仍存在、单份≤10MB，类型仅PDF/PNG/JPEG。已用于公告或审批的附件不能转为文档；请重新上传，以保留原业务授权。文档附件也不能再次用于公告；历史文件保护记录不随替换或下架删除。其他管理员修改时需重新上传所保留的附件。
- 发布校验可解析性：不支持损坏/加密PDF；PDF须1–200页、每页宽高≤3000磅且大于0，图片≤2000万像素。仅校验文件头的旧附件上传接口保持兼容，文档发布增加可预览性校验。Word/Excel、音视频、压缩包不支持。
- 水印包含登录查看人的姓名、工号及上海查看日期，烧录于内容区并有页脚。PDF由 [Apache PDFBox](https://pdfbox.apache.org/3.0/migration.html) 逐页渲染，预览与导出均不传递原始内容流、图层或图片元数据；预览长边至多约1800像素，下载PDF为栅格化水印版（不保留文字选择、链接和表单），图片导出PNG。服务端须具备中文字体（如Noto CJK；后端Docker镜像已安装），确保中文姓名可显示。
- 所有水印二进制响应 `Cache-Control: no-store`；预览图片本身也带水印。禁止下载代表不提供完整导出，预览图片仍可截图/保存，不是DRM。允许下载后已导出的水印件不能远程收回。
- 普通员工访问任何当前或历史文档附件的 `GET /attachments/{uuid}` 均403，不新增静态文件服务。管理员在后台可通过该鉴权接口取回原件；原公告/审批独立附件授权不变。
- 发布、重新上架给当前接收人发系统通知；修改已发布文档只通知新进入范围的人，操作者本人不重复接收。修改下架文档不发通知，待重新上架统一通知。增加 `document_id` 的系统通知可从三端进入对应文档；已下架/失去权限则显示中文原因；不进入审批待办，复用统一安卓 FCM，不新增工作台汇总。
- 首次**成功生成预览页**记已读，详情、预览元数据和下载不记已读；修改和重新上架不清除首次时间。写操作及预览使用事务行锁，下架与预览授权顺序一致。审计复用原表：`PUBLISH_DOCUMENT`、`UPDATE_DOCUMENT`、`WITHDRAW_DOCUMENT`、`REPUBLISH_DOCUMENT`。
- 未登录401，越权403，输入/页码/文件格式错误400，不存在404；错误为中文。普通员工不能创建上传公司文档或指定个人发送；不做网盘、协同编辑、Wiki、多级目录、权限矩阵、在线批注或全文检索引擎。

验收：`python3 scripts/verify-phase2-docs.py`（需Python Pillow，真实HTTP与像素水印检查）、`npm --prefix admin run test:e2e -- documents.spec.ts`、Maven全部测试、`npm --prefix admin run build`、`cd mobile && flutter analyze && flutter test`。新浏览器用例走管理员发布、下级员工可见/水印预览/下载、范围外不可见、禁下载、下架消失、通知失效提示、恢复和回执；Flutter覆盖逐页预览、下载入口、二进制403及通知失去权限。仅本地验证，不涉及正式签名或 IPA；安卓 FCM 见下文。

## 二期：工作台考勤与审批汇总

`GET /workbench/summary?from=2026-11-01&to=2026-11-30&scope=self`

全部需要登录。`from`、`to` 为上海日期，分别缺省为上海当前月首日、末日，含首尾最多366天。非法日期、开始晚于结束或跨度超限返回400中文错误。`scope` 仅 `self`（缺省）、`team`；可选 `userId` 在授权范围内选一名在职员工（选他人时只按该员工为申请人统计，不通过其审批任务扩大数据范围），可选 `departmentId` 仅 ADMIN/FINANCE 的 team 查询可用。非法范围参数400、越权403，403不返回统计数据。

权限按当前组织实时计算，不建立统计库或账号体系：

- EMPLOYEE：本人；本人时效涵盖本人发起，以及当前轮到本人审批的申请，办结包含本人发起或在审批链中参与的申请（同一单只计一次）。
- SUPERVISOR：另可查本人及 `manager_id=本人` 的直接下属，不递归下属的下属。
- DEPT_HEAD：另可查本人及所有 `head_id=本人` 的部门和递归下级部门；与 SUPERVISOR 并存时取并集。
- ADMIN/FINANCE：全公司，可选一个部门及全部下级；不允许其他角色按部门筛选。
- 团队/公司以申请人归属统计；在职人数、考勤和员工列表排除停用账号。历史办结时效仍包含当前组织权限范围内停用账号的历史通过/驳回单据。组织变更会改变历史查询的范围，不生成组织历史快照。

响应结构（零值也返回；数组无记录时为 `[]`）：

```json
{
  "from":"2026-11-01", "to":"2026-11-30", "scope":"self",
  "canViewTeam":false, "canFilterDepartment":false, "people":1, "departments":[],
  "attendance":{
    "normalPunches":0, "fieldPunches":0, "recordedDays":0, "approvedCorrections":0,
    "leaveDays":0, "travelDays":0, "outingDays":0, "overtimeDays":0, "overtimeCreditedDays":0,
    "daysByType":[]
  },
  "approvals":{
    "pendingForMe":0, "initiatedPending":0, "completed":0, "averageHours":0,
    "waiting":{"total":0,"withinOneDay":0,"oneToThreeDays":0,"overThreeDays":0},
    "byType":[]
  }
}
```

- `departments` 仅 ADMIN/FINANCE 返回部门选项（id/name/parent_id）；其他角色为空。`people` 是所选范围的在职人数，不是打卡人数。
- 打卡按 `punches.punched_at` 上海日期筛选。`recordedDays` 是去重的“员工ID+日期”数量，团队为人日总和，不是应出勤或全团队日期去重。补卡批准已写入 punches，正常/出勤次数及记录日仅统计该表一次；`approvedCorrections` 单独展示已批准补卡单数量，按补卡业务日期筛选，不再相加到打卡次数。
- 请假/出差/外出/加班只取当前 APPROVED，按与区间交集截取；复用现有9点/13点开始、13点/18点结束的自然日半天模型，含周末。跨期只计区间内天数，不取整单。`daysByType` 行含 `kind/leaveType/days/creditedDays`，请假按假种分组；`creditedDays` 仅对加班有调休含义，其余类型等于days。加班额度按单据保存的 `overtime_ratio` 快照乘区间内天数，默认1:1，以提交时服务端配置为准；不重新计算历史额度。
- 审批仅来自 requests 六类 LEAVE/TRAVEL/OUTING/EXPENSE/OVERTIME/CORRECTION。会议邀请、任务、文档回执不进入审批时效。`pendingForMe` 始终是登录人当前步骤待审批总数；不会因全公司筛选变为所有审批人的待办。`initiatedPending` 是所选本人/团队申请人仍 PENDING 的总数。`waiting` 本人为本人发起或当前待本人处理的去重待审单，团队为范围内申请人的全部待审单。这三项均**实时、不受日期筛选限制**。
- `completed` 仅当前 APPROVED/REJECTED 的最终办结单，以审批步骤中最终处理时间（最大已通过/驳回 `acted_at`）落入统计期为准。已撤回、撤销不计。`averageHours` 从 `requests.created_at` 到该终审时间，以小时计，保留2位小数；无办结返回0。`byType` 按 `kind/status` 分组，行含 `completed/averageHours`。不以报销支付时间代替终审时间。
- 等待从提交时刻到服务器当前上海时刻按实际经过时间分段：`withinOneDay` ≤24小时，`oneToThreeDays` >24且≤72小时，`overThreeDays` >72小时；仅展示，不催办、不升级、不发通知。
- 汇总统计全部记录，不受已有列表200条分页限制。同一查询使用只读一致性事务。不新增表或迁移，保持Flyway V8。

下钻：`GET /workbench/members?departmentId=12&search=姓名或工号&offset=0` 返回授权范围内在职员工 `id/name/employee_no`，每页最多200条。offset非负，search最多100字；员工只返回自己，主管/负责人按上述范围；部门参数仍仅ADMIN/FINANCE可用。三端可选员工后复用 `GET /punches?userId=...&from=...&to=...&offset=0`，该接口增加相同团队范围内单个在职员工的读取权限；不传userId时非管理员仍默认本人，原管理员全量查询不变。申请下钻仍使用原申请/消息列表及其原授权，不因此开放团队申请正文、票据或附件。

三端均保留原 `/messages/summary` 和消息中心五类，汇总不读写已读状态、不产生通知。后台保留组织数字与入门引导；员工网页“提交申请”工作区、Flutter工作台默认展示本人，详情可改日期、有权限时可选团队及部门。不提供班次、迟到、早退、缺卡、应出勤、工时或扣款规则，也不提供BI、自定义报表、图表引擎或统计导出。

验收：先 `source scripts/env.sh && python3 scripts/verify-phase2-workbench.py` 生成独立验收数据和 `.runtime/workbench-fixture.json`，再 `npm --prefix admin run test:e2e -- workbench.spec.ts`。HTTP覆盖401/403/400、组织权限、部门递归、停用历史、253条打卡分页与全量统计、半天裁剪、补卡去重、六类终审48小时和等待分段、非审批数据隔离及消息汇总不变。Flutter `flutter analyze && flutter test` 覆盖权限筛选、日期错误、数字展示和下钻。全量回归仍使用既有脚本与浏览器用例。本地连续验收若触发每IP 15分钟100次登录限流，应等窗口结束或仅在本机开发Redis清理本机IP的登录限流测试计数，不改登录规则和会话。

## 三期：资产领用（V9）

全部路径以 `/api` 为前缀，沿用既有登录、组织、附件、系统通知和审计。未登录401、越权403、参数错误400、库存/状态/重复编号冲突409、不存在404，错误信息为中文。业务时间为 Asia/Shanghai。V9只新建资产表及通知关联列，不改写原业务数据。

### 权限和台账

仅 ADMIN、FINANCE（财务/行政）可维护分类和台账、调整库存/状态、发放、拒绝发放、确认收回及查询全员记录。其他员工只能查可申请目录、本人申请及本人资产；指定直属上级仅在申请待审批时可看该审批详情和处理，无部门负责人兜底或多级审批。管理员身份不能代替指定上级审批。

| 接口 | 参数和行为 |
| --- | --- |
| `GET /assets/categories` | 管理员/财务查全部分类（含停用） |
| `POST /assets/categories` | `{name,kind,enabled}`；kind为ITEM按件、CONSUMABLE消耗品 |
| `PUT /assets/categories/{id}` | 同上，名称及启用可改，管理方式不可改 |
| `GET /assets` | 管理台账；可选categoryId、status、offset；每页至多200条 |
| `POST /assets`、`PUT /assets/{id}` | `{categoryId,code,name,brand,serialNumber,purchaseDate,location,note,attachmentId,unit,enabled}`；分类不可改，日期为YYYY-MM-DD，可选值可传null |
| `POST /assets/{id}/adjust` | 按件`{status,reason}`，status仅AVAILABLE/REPAIR/SCRAPPED；消耗品`{delta,reason}`，delta为非零整数（绝对值≤1000000），库存结果0–100000000；原因必填 |
| `GET /assets/catalog` | 所有登录人可用，只返回启用按件分类/消耗品的id、name、kind、unit和available数量，不暴露领用人或台账明细；ITEM的id为分类ID，CONSUMABLE的id为资产ID |

按件编号公司内唯一（去首尾空格），重复409；状态AVAILABLE在库、ASSIGNED已领用、REPAIR维修中、SCRAPPED已报废。新增按件为在库；消耗品初始库存0，通过有原因的库存调整入库。已领用按件资产任何手工状态调整均409，须先确认收回。停用、报废不删除历史，也不解除领用关系。附件复用 `POST /attachments` 与鉴权下载，仅PDF/PNG/JPEG且≤10MB；新关联须为操作人本人上传，编辑可保留该资产原附件。资产附件不向员工目录开放，文档原件保护保持不变。

### 申请、审批、发放与归还

| 接口 | 参数和行为 |
| --- | --- |
| `POST /assets/requests` | `{categoryId,consumableId,quantity,purpose,expectedDate,needsReturn,returnDate}`；categoryId按件分类与consumableId消耗品二选一；数量1–100000整数，用途必填≤2000字，期望日期必填 |
| `GET /assets/requests` | scope=mine（默认）/pending/all；all仅管理员/财务；可选userId、departmentId、assetId、offset，按资产筛选同时覆盖消耗品与按件分配；每页至多200条 |
| `GET /assets/requests/{id}` | 授权详情、assignments编号分配及canApprove/canManage/isOwner操作标记 |
| `POST /assets/requests/{id}/approve` | 指定直属上级：`{approved,note}`；驳回原因必填，说明≤1000字 |
| `POST /assets/requests/{id}/withdraw` | 本人在PENDING/READY可撤回，无请求体 |
| `POST /assets/requests/{id}/issue` | 管理员/财务：按件`{assetIds:[编号对应的资产ID],note}`，须等于申请件数且不可重复；消耗品`{note}`扣申请数量；拒发`{rejected:true,note}`原因必填 |
| `GET /assets/mine?offset=0` | 本人按件分配和历史；returned_at为空代表仍在名下。消耗品历史见我的申请；每页至多200条 |
| `POST /assets/requests/{id}/return` | 本人按件领用后`{note}`发起整单归还登记，不立即释放资产；重复登记409 |
| `POST /assets/requests/{id}/confirm-return` | 管理员/财务：`{note,repair:false}`确认整单收回，也可无需员工登记直接收回；说明必填，repair=true整单登记维修，否则在库 |
| `GET /assets/outstanding/{userId}` | 管理员/财务获取`{count}`未归还件数；后台账号编辑弹窗中文提示，仅提示、不阻止停用 |

直属上级在提交时取当前有效manager_id并保存到申请；没有有效直属上级直接READY待发放，否则PENDING待审批。上级批准进入READY；上级驳回或行政拒发进入REJECTED。发放按件逐件行锁并建分配，数据库唯一索引保证一个编号只能有一个当前领用人；整笔事务，任一编号不可用则回滚全部。消耗品原子扣库存，库存不足409且不变负数。申请不预占资产或库存，发放时重新检查分类/资产启用状态。

按件needsReturn默认true，false可选；消耗品强制false。预计归还日期可选，需归还时不得早于期望领用日期。需归还按件发放后IN_USE使用中，其余ISSUED已发放。按件即使原选无需归还，也保留名下记录并可收回。员工归还登记保持当前状态，return_requested=true；行政确认后RETURNED已归还，释放全部分配并记录归还时间。WITHDRAWN已撤回仅允许发放前。当前只支持整单发放和整单归还，不支持部分发放/归还；不做采购、折旧、供应商、扫码硬件或库存进销存。

### 消息与验收

`GET /messages?category=pending` 合并原六类审批、待办任务和资产，资产条目标记`item_type=ASSET`、title为「资产领用」。`GET /messages/summary.pending` 增加指定上级待审批、ADMIN/FINANCE待发放和待确认归还的申请数（按单计数）。原消息分类、未读和原审批/任务字段保持兼容。资产我的申请从资产页面查看，不改变消息中心“我发起的”原六类口径。

批准、驳回、发放、拒发和确认归还向申请人写入原notifications系统通知，以asset_request_id关联，三端可打开详情；安卓 FCM 由统一通知入口发送。所有维护和状态流转写原audit_logs，库存调整记录数量/状态及原因。工作台六类审批统计继续仅取原requests，不计资产，全部考勤/审批汇总规则不变。

本地验收：`source scripts/env.sh && python3 scripts/verify-phase3-assets.py`；浏览器 `npm --prefix admin run test:e2e -- assets.spec.ts` 覆盖后台登记、员工网页申请、上级混合待办审批、行政发放、员工归还及确认、消耗品扣减与移动网页。另跑Maven全部测试、前端构建、既有HTTP和浏览器回归，以及 `cd mobile && flutter analyze && flutter test`（资产组件测试含默认归还、表单冲突、归还登记、混合待办审批返回）。测试仅创建本地独立业务数据，不提交上传文件、构建产物或数据库。

Flutter真实接口验收复用上述HTTP脚本创建的独立账号：从 `.runtime/assets-http-result.json` 读取 `prefix`，在 `mobile/` 下设置 `OA_ASSET_PREFIX=<prefix>` 后运行 `flutter test test/assets_live_test.dart`，覆盖员工申请、直属审批、行政发放、员工归还、行政收回、消耗品扣减及撤回。未设置该变量时此项跳过，常规组件测试仍执行。

## 三期：合同管理（V10）

所有路径以 `/api` 为前缀，必须登录。未登录401、越权403、参数无效400、编号重复/状态冲突409、不存在404；错误为中文。V10仅增量添加合同、审批步骤、附件关联、提醒去重表、通知contract_id及workflow_config.contract_threshold，兼容PostgreSQL 15，不改写V1–V9。

### 台账和查询

| 接口 | 参数与行为 |
| --- | --- |
| `POST /contracts` | 登记草稿，返回`{id}`；请求字段见下文 |
| `PUT /contracts/{id}` | 经办人修改草稿/已驳回/已撤回，返回`{id}`；审批中及通过后不能修改，409 |
| `DELETE /contracts/{id}` | 仅经办人删除草稿；其余状态409 |
| `GET /contracts` | `scope=visible`（默认）/mine/pending，可选status、type、departmentId、counterparty、expiryFrom、expiryTo、offset（默认0）、limit（默认200，1–200） |
| `GET /contracts/{id}` | 授权详情，包含files、steps、workflow_status及isOwner/canApprove/canArchive |
| `GET /contracts/summary` | `{expiring30}`：可见范围内待归档/履行中、到期日在上海今天至今天+30日（含首尾）的数量 |
| `GET /contracts/export` | 仅ADMIN/FINANCE，支持与列表相同的状态、类型、部门、对方及到期区间筛选；导出所有符合条件记录，无分页截断；UTF-8 BOM CSV、中文表头和状态，防公式注入，写EXPORT_CONTRACTS审计 |

登记/修改示例：

```json
{
  "code":"", "name":"年度服务合同", "type":"SERVICE",
  "counterparty":"对方公司", "contact":"联系人", "phone":"13800000000",
  "amount":50000, "signedDate":"2026-11-12", "effectiveDate":"2026-12-01",
  "expiryDate":"2027-11-30", "performance":"按月履约", "note":"备注",
  "attachmentIds":[]
}
```

name、type、counterparty、signedDate、effectiveDate必填。code最多100字，留空生成`HT-上海年份-六位递增序号`（数据库全局序列、不按年重置），手填去首尾空格、公司内唯一；name/counterparty最多200字，contact100、phone50、performance/note5000字；附件最多30份。amount非负、至多12位整数和2位小数，可为null或0表示无固定金额；expiryDate可null表示长期，否则不得早于生效日。userId默认本人，仅ADMIN可在创建时指定其他有效经办人；修改不可换人。所属部门按创建时经办人主部门记录。

类型：PURCHASE采购、SALES销售、SERVICE服务、LEASE租赁、LABOR劳务、OTHER其他。
状态：DRAFT草稿、PENDING审批中、REJECTED已驳回、WITHDRAWN已撤回、TO_ARCHIVE待归档、ACTIVE履行中、EXPIRED已到期、TERMINATED已终止。列表/详情/导出实时按Asia/Shanghai日期计算：expiry_date早于今天且未终止即显示EXPIRED；workflow_status保留实际流程状态，操作按该字段判断，故历史到期草稿仍可修改、历史待归档仍可归档。当日到期尚不显示已到期。

查询权限：本人经办；直属上级看manager_id指向本人的直接下属；DEPT_HEAD看head_id为本人的部门及递归下级；ADMIN/FINANCE全部。列表先施加权限再筛选/分页，仅返回数组、不返回越权总数；直接读取他人合同403，任意筛选不能扩大权限。departmentId为精确部门筛选。pending为当前轮到本人处理的合同，mine为本人经办；消息中心“我发起的”继续保持原口径，我的合同在合同页查询。

### 审批与归档

| 接口 | 参数与行为 |
| --- | --- |
| `POST /contracts/{id}/submit` | 经办人提交DRAFT/REJECTED/WITHDRAWN，无请求体，保存新revision审批链 |
| `POST /contracts/{id}/approve` | 当前审批人`{approved:true,note:""}`通过；`approved:false`驳回，note必填，最多1000字；不轮到本人403 |
| `POST /contracts/{id}/withdraw` | 经办人终审前PENDING可撤回；已终审409 |
| `POST /contracts/{id}/archive` | 经办人或ADMIN在TO_ARCHIVE，`{attachmentIds:["上传UUID"]}`，1–30份本人上传盖章版，进入ACTIVE |
| `POST /contracts/{id}/terminate` | 经办人或ADMIN，`{reason:"终止原因"}`，必填≤1000字；草稿/已终止409，保留记录与原因 |

审批链固定为：直属上级（未配置则跳过）→ 金额**超过**contract_threshold时追加部门负责人（与上级同人跳过重复）→ FINANCE或ADMIN任一人终审。阈值默认50000元，空金额/0/等于阈值不追加负责人；复用`GET/PUT /admin/workflow`，返回contract_threshold，保存字段contractThreshold，非负至多两位小数；旧客户端不传保留原值。提交后链已快照，改阈值不改已提交审批链；驳回/撤回重提建立新revision，保留旧审批记录。缺有效负责人/终审人或自批配置返回中文400；申请人不得审批自己的合同。终审通过进入TO_ARCHIVE，必须上传盖章版并归档后ACTIVE。事务和合同行锁串行处理重复审批/撤回/归档，编号并发重复由唯一约束返回409。

### 附件、提醒和消息

附件先复用`POST /attachments`上传（PDF/PNG/JPEG，每份≤10MB），登记时attachmentIds关联本人上传附件，修改可保留该合同现有附件。文档原件/公告/审批/资产已用附件不可重新归为合同，合同附件也不可关联成文档绕过权限。

`GET /contracts/{id}/files/{uuid}?preview=true`按合同可见权限内联预览；省略preview或false下载并写DOWNLOAD_CONTRACT_FILE审计；预览写PREVIEW_CONTRACT_FILE审计。仅能访问本合同已关联附件，带no-store与nosniff；合同扫描件按权限返回原文件，不使用文档资料的个性化水印重绘。通用`GET /attachments/{uuid}`对合同附件一律403，须走合同接口。

每天上海08:00运行提醒，并在登录用户读取`GET /messages/summary`时补发。只处理TO_ARCHIVE/ACTIVE，expiry_date落在今天至今天+30或今天+7日（含首尾），按合同与节点唯一约束各发一次；服务停机跨过精确节点后，只要仍未到期即可补发，7天内首次补发可同时收到两条。已到期/终止不发，长期不发；同一合同修改到期日后不重置已发送节点。使用原notifications，contract_id关联，经办人收取，复用统一安卓 FCM。

提交、分级通过、驳回、撤回、归档、终止及到期提醒向经办人写系统通知；提交/中间通过还通知下一审批人，终审组通知启用的ADMIN/FINANCE（排除申请人）。消息待办`GET /messages?category=pending`合并合同，`item_type=CONTRACT`、title为合同名称；三端显示「合同」并进入详情审批。`GET /messages/summary.pending`在原六类审批、任务及资产之上按合同加一，不增加消息大类。工作台统计仍仅取原requests六类，合同不参与审批时效。登记、删除、提交、审批、撤回、归档、终止、附件读取和导出均复用audit_logs。

### 本地验收

```bash
source scripts/env.sh
python3 scripts/verify-phase3-contracts.py
npm --prefix admin run test:e2e -- contracts.spec.ts
```

HTTP覆盖未登录401、越权403及列表隔离、编号409、分页、导出BOM与权限、50000边界、超阈值、同人去重、审批中锁定、驳回/撤回修改重提、归档、附件权限/跨业务隔离、到期与提醒两节点各一次、审计、原待办合并及六类工作台不变。浏览器覆盖员工移动网页起草提交、上级待办驳回、修改重提、上级/负责人/财务审批、盖章归档、管理员筛选导出和原待办数字保留。

Flutter常规`flutter analyze && flutter test`覆盖中文合同表单、必填/冲突保留数据、长期及无固定金额、详情归档和合同待办导航。真实接口验收先运行上述HTTP脚本，从`.runtime/contracts-http-result.json`读取prefix，在mobile目录设置`OA_CONTRACT_PREFIX=<prefix>`后执行`flutter test test/contracts_live_test.dart`，使用独立测试账号完成登记、驳回重提、三级审批、附件归档下载与终止；未设置变量时跳过该真实接口用例。另保留Maven全量、Vue构建、既有HTTP与浏览器回归通过记录，未受后续改动影响的不重复运行。不生成APK、iOS签名或IPA，不启动cloudflared/watchdog/8088 nginx。

## 三期：访客管理（V11）

定位为100人以内公司的简单来访登记台账，无审批流、门禁/闸机、人脸或证件扫描，不发送短信或验证码；签到通知复用统一安卓 FCM。以下路径均带 `/api` 前缀且必须登录；未登录401，越权403，参数和非法状态流转400，访客码分配重试耗尽或数据库唯一冲突409，错误为中文。V11只增量建表/索引及通知关联，SQL兼容PG15；本机实际验证数据库为PG17，未连接或迁移生产库。

### 角色和隐私

本期前台只用现有 `ADMIN`，**不新增 RECEPTION**。员工看自己作为接待人的记录；直属上级还可看 `users.manager_id` 指向自己的直接下属；ADMIN看全部。FINANCE、DEPT_HEAD没有额外访客特权。列表、筛选、汇总先限制可见范围，不返回全公司总数；指定无权接待人或直接访问无权记录403。员工端「我的访客」固定 `mine=true`，管理入口可以查全部可见记录。

仅ADMIN可签到、签离、选在职接待人临时登记、读取接待人选项和导出。普通预约只能由本人作为接待人发起，ADMIN也不能通过普通预约替他人登记；代登记请用临时来访。取消仅限该条接待人。列表手机号对所有角色一律脱敏（如`138****1234`），详情完整手机号仅ADMIN和该条接待人可见，上级仍脱敏；ADMIN导出含完整手机号。

访客姓名、手机号等仅用于来访登记，建议按公司制度定期清理已离开、已取消、已过期记录；本期不做自动删除。

### 登记和状态流转

| 接口 | 参数与行为 |
| --- | --- |
| `POST /visitors` | 本人预约，返回详情及六位访客码 |
| `POST /visitors/temporary` | ADMIN临时登记，必填hostId，直接已到访并记录签到时间/通知 |
| `GET /visitors/{id}` | 权限内详情，含isOwner及访客码 |
| `POST /visitors/{id}/cancel` | 接待人取消待到访，无请求体 |
| `POST /visitors/check-in` | ADMIN凭码签到，`{"code":"001234"}` |
| `POST /visitors/{id}/check-in` | ADMIN搜索定位后签到，无请求体 |
| `POST /visitors/{id}/check-out` | ADMIN签离已到访记录，无请求体 |
| `GET /visitors/hosts` | ADMIN读取在职接待人选项，id/name/employee_no |

预约及临时登记请求示例（时间均为Asia/Shanghai本地时间，不带时区）：

```json
{
  "name":"张访客", "phone":"13812341234", "company":"来访单位",
  "purpose":"业务交流", "people":2, "companions":"李同行",
  "startAt":"2026-11-20T09:00:00", "endAt":"2026-11-20T11:00:00",
  "plate":"", "note":""
}
```

必填name≤100字、phone为7–20位数字、company≤200字、purpose≤1000字、people为1–100整数、startAt/endAt。随行姓名companions可空≤1000字、plate可空≤30字、note可空≤2000字。endAt须晚于startAt且晚于当前时间。临时登记另外传hostId，须为启用账号；一般预约hostId省略或只能为本人。

响应保留数据库下划线字段（host_id、host_name、start_at、end_at、checked_in_at、checked_out_at），含name/phone/company/purpose/people/companions/plate/note/code/status/temporary；详情含isOwner，不暴露内部active_code。

状态：`WAITING`待到访、`ARRIVED`已到访、`LEFT`已离开、`CANCELLED`已取消、`EXPIRED`已过期。数据库保留WAITING；列表、详情和导出以 `end_at < 上海当前时间` 且WAITING实时计算EXPIRED，无每日定时任务，等于结束时间尚未过期。只有有效WAITING可签到，只有ARRIVED可签离；取消、过期、重复签到、未签到签离、重复签离等均400且中文提示。已到访不因预计结束时间而自动签离或过期。

访客码保留前导零，为6位数字。只在有效WAITING和ARRIVED中唯一；分配前释放过期预约的占用，LEFT/CANCELLED即刻释放；历史code字段保留。事务级PostgreSQL advisory lock串行化分配和流转，配合active_code唯一约束，随机碰撞最多重试100次后409。凭码接口只匹配当前有效记录，不会选中复用同码的历史记录。访客码仅用于前台登记查询，不作为门禁凭证。

签到和临时登记向接待人插入原notifications系统通知，visitor_id唯一索引保证同一来访只发一次；失败流转不写通知。复用原消息中心及统一安卓 FCM，不新增消息大类。访客完全不加入`GET /messages?category=pending`或`GET /messages/summary.pending`；原六类审批及其工作台时效统计不变。

### 列表、看板和导出

- `GET /visitors`：from/to为日期，筛选预计时间段与该上海日期区间有交集的记录；status为上述五种状态；hostId为接待人；keyword模糊匹配姓名、单位、访客码、接待人姓名；mine=true仅本人。offset默认0且非负，limit默认200、范围1–200。按预计开始时间及ID倒序，返回数组，无越权总数。
- `GET /visitors/summary`：返回todayReservations、present，均按当前访客可见范围。前者为预计开始日期在上海今天的预约记录数（不含临时登记，包含取消记录），后者为所有ARRIVED记录的people合计（含随行人及跨日未签离记录）。工作台展示两项，不受工作台审批统计筛选影响；「今日访客」列表按预计时间与今天交集筛选。
- `GET /visitors/export`：仅ADMIN，支持上述过滤项（无分页），UTF-8 BOM CSV，含完整手机号、人数/随行人/接待人、预计时段、访客码、中文状态、签到签离、车牌和备注。转义引号并防电子表格公式注入，写EXPORT_VISITORS审计；非ADMIN403。
- 预约RESERVE_VISITOR、取消CANCEL_VISITOR、签到CHECKIN_VISITOR、签离CHECKOUT_VISITOR、临时登记REGISTER_TEMP_VISITOR均写原audit_logs。

### 本地验收

```bash
source scripts/env.sh
python3 scripts/verify-phase3-visitors.py
npm --prefix admin run test:e2e -- visitors.spec.ts
cd mobile
/home/box/flutter/bin/flutter analyze --no-fatal-infos
/home/box/flutter/bin/flutter test
OA_VISITOR_FIXTURE=/workspace/oa-system/.runtime/visitors-http-result.json /home/box/flutter/bin/flutter test test/visitors_live_test.dart
```

HTTP覆盖未登录、越权、直属范围、手机号脱敏、参数、取消/过期/签到/签离、一次通知、16笔并发码唯一、过期码复用、临时登记、BOM导出及审计、原待办及六类工作台结果不变。VisitorTest另覆盖随机碰撞重试、耗尽409、CSV转义。浏览器复用HTTP生成的token避免重复登录；覆盖员工移动网页预约/取消、ADMIN凭码签到签离/临时登记/导出、原合同/资产/消息/工作台入口、无控制台错误和响应式截图。

Flutter新增访客输入校验与北京时间、必填/409保留表单、我的访客/访客码/取消组件测试；真实API测试复用本地HTTP结果会话完成预约、签到签离、通知及取消。未设置OA_VISITOR_FIXTURE时真实测试跳过；该结果文件含会话，仅存.runtime，不得提交或外传。静态检查遗留合同测试文件一条不影响运行的info（unnecessary_brace_in_string_interps），访客代码无告警。仅补跑受影响回归，复用之前已通过且未受改动影响的验收；无iOS打包、正式签名、IPA或APK。本期不启动cloudflared、watchdog、8088 nginx，不运行dev-down.sh，不部署到MacBook或生产环境。


## 安卓 FCM 推送（V12）

仅安卓 `com.company.oa_employee` 接入 Firebase Cloud Messaging HTTP v1（Firebase Admin SDK）。iOS/APNs、国内安卓厂商通道、网页管理后台及 `/staff/` 浏览器推送均未接入。公告仍走原公告表和回执，本期仅推送新写入 `notifications` 的系统通知。

### 设备登记与注销

复用现有 Bearer 登录、首次改密限制和 `login_sessions`，不新增会话体系。

| 方法与路径 | JSON 输入 | 结果 |
| --- | --- | --- |
| `POST /push/tokens` | `{"token":"FCM注册token","platform":"ANDROID","deviceName":"员工手机"}` | `{"message":"推送设备已登记"}` |
| `DELETE /push/tokens` | `{"token":"FCM注册token"}` | `{"message":"推送设备已注销"}` |

- token 必填、仅非空白可打印 ASCII、最多2048字符；platform 必须为 `ANDROID`；deviceName 可省略或为 null，最多100字符。无登录401，格式无效400，中文错误；首次未改密403。
- V12 新增 `push_tokens`，token 全表唯一。同一 token 再登记会换绑到当前用户/当前登录会话并刷新更新时间；用户可有多台设备。表的 `IOS` 枚举仅预留，接口不接受且不发送。
- 显式注销仅删除当前用户所属 token，重复注销仍成功。`POST /auth/logout` 同时删除当前会话登记的 token。员工端退出先显式注销，再退出原会话；撤销、过期、改密失效会话和停用账号不会继续收到推送。token 明文不写入审计或日志。

### 通知发送与回退

审批（含抄送）、会议、任务、文档、资产、合同、访客全部通过 `NotificationService` 写入；原有文案、接收人和关联字段保持不变。访客的冲突忽略仅在 `RETURNING` 确实返回新行时触发。事务提交后放入有界异步线程池，向全部有效安卓 token 发送；无事务调用在写入成功后异步发送，事务回滚不发送。

可见 title/body 均使用原通知文案；data 值均为字符串，包含 `notification_id`，以及存在的 `request_id`、`meeting_id`、`task_id`、`document_id`、`asset_request_id`、`contract_id`、`visitor_id`。Flutter 点击通知统一进入原消息中心，用户可从同一通知继续进入已有详情并执行原鉴权。前台收到时提示「收到新的系统通知，请查看消息中心」，可点「查看」。安卓13及以上申请通知权限，拒绝后不会反复弹窗，打卡和审批照常；授权后登录/恢复会话登记 token，刷新自动重登。

服务端用环境变量 `FCM_CREDENTIALS`（或备用 `GOOGLE_APPLICATION_CREDENTIALS`）指定服务账号文件路径，默认空。文件仅放在运行机器安全目录，不要提交私钥、整份服务账号 JSON、测试夹具或日志；客户端 `mobile/android/app/google-services.json` 仅为公开客户端配置。配置后重启开发服务即可启用，Firebase 项目须已启用 FCM HTTP v1 并允许服务账号发送。

缺少凭证、网络/权限错误、队列满或其他发送失败均不回滚主业务或已入库通知；不保证离线重试或投递成功。HTTP v1 `UNREGISTERED` 会删除失效 token；暂时失败保留 token。设备仍需可用的 Google Play services 与 FCM 网络连接。`GET /messages?category=notifications` 和 `GET /notifications` 始终是可靠后备，手动刷新仍可看到同一通知，推送不自动标记已读。

### 本地测试

`PushHttpTest` 使用真实随机端口 HTTP、PostgreSQL 和 Redis（逻辑库14），仅 mock FCM 发送器；不会读取服务账号或访问真实 Firebase。先启动本地开发数据库，创建专用 `oa_push_test`（可通过 `PUSH_TEST_DATABASE_URL` 覆盖），再运行所有 Maven 测试：

```bash
source scripts/env.sh
PGPASSWORD="$DATABASE_PASSWORD" createdb -h 127.0.0.1 -p 55432 -U oa oa_push_test # 仅首次，使用本地开发库密码
mvn -f backend/pom.xml -Dmaven.repo.local="$PWD/.tools/m2" test
cd mobile
/home/box/flutter/bin/flutter test
/home/box/flutter/bin/flutter analyze --no-fatal-infos
```

Flutter `push_test.dart` 用假设备和 mock HTTP 验证权限拒绝、登记、刷新、退出竞态及前后台点击，不连接 Firebase。真实手机投递需要后续在已配置服务端凭证的开发环境，用授权安卓设备验收；本次无需重打 APK、部署或修改任何苹果配置。

### 外部审查修复：审批快照与管理员处理

组织调整只影响新提交的审批；合同按 `contract_steps` 当前版本、当前步骤的 `approver_id` 授权，未指定人员的终审仍由财务或管理员处理。合同可见范围包括经办人、历史指派/实际审批人、改派前后人员、管理员、财务及原部门查看范围。改派前人员保留历史查看权限，失去当前步待办和审批权。审批列表、消息待办与详情使用同一当前步骤条件，不能审批自己的申请。

- `POST /api/contracts/{id}/reassign`、`POST /api/assets/requests/{id}/reassign`、`POST /api/requests/{id}/reassign`：仅 ADMIN，当前单据须 PENDING。请求 `{ "approverId": 123, "note": "人员交接" }`；说明必填，最多 1000 字。目标必须在职有效且不是申请人或当前审批人；只改当前待审步骤，后续步骤快照保持不变。通过统一 NotificationService 写消息并触发事务提交后的 FCM。
- `POST /api/contracts/{id}/proxy-approve`、`POST /api/assets/requests/{id}/proxy-approve`：仅 ADMIN，`{ "approved": true, "note": "交接代办" }`。驳回必须说明原因；不可自批。合同推进下一级或进入待归档/驳回，资产进入 READY/REJECTED。
- 一般工作流仍使用 `POST /api/requests/{id}/decision`，字段为 `approve`、`comment`；ADMIN 可代当前步骤审批，原审批人停用也可处理。步骤新增 `actor_id`、`actor_name`，管理员操作自动加「管理员代批」备注。被改派的有效员工无需额外审批角色即可处理自己的步骤。
- 合同、资产、一般审批详情新增 `reassignments`（按事件顺序）：`id`、`business`、`target_id`、`revision`、`step`、`previous_approver_id/name`、`approver_id/name`、`actor_id/name`、`note`、`created_at`。原步骤指派历史不会被覆盖。资产新增 `approval_actor_id`、`approval_acted_at`（实际操作人与时间）；合同使用既有 `actor_id/name`。
- 停用员工前检查在途一般审批、当前版本合同当前及后续待审步骤、PENDING 资产领用，同时保留组织负责人、下属、财务配置和末位管理员检查。需先改派、代批或处理完对应单据。资产 READY/归还发放为财务/管理员角色池，不绑定某一账号。

### 打卡定位溯源（V13）

`POST /api/punches` 及开发模拟接口的 `punch` 对象新增可选字段：

| 字段 | 含义 |
| --- | --- |
| `locatedAt` | ISO-8601 带时区时间戳，新客户端传 UTC `Z`；记录真实定位采集时刻 |
| `locationSource` | `LIVE` 现场实时（含 Android LocationManager 新定位）、`WARMUP_CACHE` 预热缓存、`LAST_KNOWN` 系统最近定位、`STALE_FALLBACK` 超时旧缓存、`UNKNOWN` 未知/旧客户端 |

声明具体来源时必须传采集时间；时间不能超过未来 2 分钟或过去 24 小时。预热和系统最近定位最多 30 分钟；超时旧缓存仅正常打卡允许，出勤禁止。来源白名单校验；旧客户端未传时保存 `UNKNOWN`/NULL，不影响原网络与外出审批校验。补卡审批生成 `CORRECTION` 来源，无定位采集时间；客户端不能自行声明补卡来源。

打卡列表与 CSV 返回 `located_at`、`location_source`，列表同时提供 `locatedAt`、`locationSource` 别名。历史记录可为 NULL，显示未知/历史记录；定位来源与「现场打卡/补卡」记录类型分列。网页获取浏览器实时定位（禁止复用缓存），提交 UTC 采集时间及 `LIVE`；Flutter 使用原始 Position.timestamp，预热缓存保持原时间，提交前显示来源与采集时间供确认。

### 写事务锁隔离

打卡使用 `(20261112, hashint8(userId))` 事务锁，一般审批决定/撤回使用 `(20261113, hashint8(requestId))`；同员工余额仍通过用户行锁串行。组织维护使用独立排他键 `20261114`；读取审批链、改派、公告/文档接收范围快照使用该键的共享锁，防止停用与新指派交错。共享快照之间可以并行，不会阻塞打卡。文档文件解析在开启数据库写事务及获取锁前完成，事务内重新检查附件权限和绑定；重新上架核对附件未被替换。合同与资产写操作使用既有单据行锁，发放按资产 ID 排序加锁；文档附件按 UUID 排序加锁。日程 `630206` 和访客 `110011` 保留各自独立的业务锁，不与其他业务共用。每个路径最多一把排他业务 advisory lock，无交叉命名空间反序获取。

## 第二阶段优化（V14 / V15 / V16）

所有业务日期、`timestamp` 打卡和半天边界按 Asia/Shanghai 解释；带时区的催办时间为 ISO 8601。原 `POST /api/punches`、消息 `pending`、既有导出和工作台字段保持兼容。

### 考勤规则与日历

- ADMIN `GET /api/admin/attendance-rules` 返回单行规则；`PUT` 接收 `enabled,work_start,work_end,late_grace_minutes,early_grace_minutes,workdays,effective_from`。时间如 `09:00`，工作日为 ISO 星期串 `1,2,3,4,5`，宽限 0–240 分钟，上班必须早于下班。保存写 `SAVE_ATTENDANCE_RULES` 审计。
- ADMIN `GET /api/admin/work-calendar?year=2026`；员工可用 `GET /api/work-calendar?year=2026`。`POST /api/admin/work-calendar` 接收 `{day:"2026-10-01",kind:"HOLIDAY",name:"国庆节"}`，按日期覆盖；`DELETE /api/admin/work-calendar/{day}` 删除。
- ADMIN `POST /api/admin/work-calendar/batch` 接收 `{text:"2026-10-01,HOLIDAY,国庆节\n2026-10-10,WORKDAY,调休上班"}`，最多400行。返回 `success,failed,rows:[{line,success,message?}]`，行号从1开始。有效行保存，错误行给中文原因。名称最多100字，类型仅 HOLIDAY/WORKDAY。所有写操作记录审计。示例仅演示格式，请以国务院办公厅当年通知为准。
- `GET /api/attendance-policy` 保留 `correction_days,overtime_ratio,leave_threshold`，新增 `attendanceRules`，含规则启用、起始日期、上下班时间、宽限和工作日。
- `GET /api/attendance/daily?userId=&from=YYYY-MM-DD&to=YYYY-MM-DD`：默认本人，含首尾最多366天。每行 `day,status,firstPunch,lastPunch,punches,rulesEnabled,expected,lateMinutes,earlyMinutes,missingPunch,leaveDays,field,requestIds`。状态 NORMAL 正常、LATE 迟到、EARLY 早退、LATE_EARLY 同时异常、MISSING 缺卡、LEAVE 请假、FIELD 外勤出勤、REST 休息、EXCLUDED 未纳入统计、FUTURE 未来、IN_PROGRESS 当天进行中；关闭规则时 RECORDED/PUNCHED 只展示事实。
- `GET /api/attendance/summary?month=YYYY-MM&departmentId=&userId=`：按人返回 `user_id,employee_no,user_name,department_name,expectedDays,normalDays,lateCount,lateMinutes,earlyCount,earlyMinutes,missingCount,leaveDays,fieldDays,restDays,rulesEnabled`。权限复用工作台：员工本人、主管直属下级、部门负责人所辖树、ADMIN/FINANCE 公司范围；仅公司范围角色可传部门筛选。越权403。工作台 `attendance` 新增 `anomalies:{lateCount,earlyCount,missingCount}`，使用其选定日期与人员范围。

实时判定不回写 punches：节假日免卡、补班日优先于周几。迁移当天（北京时间）之前默认未纳入；管理员可调整 effective_from。入职前不统计；V16 新增 users.disabled_at（timestamptz），不回填旧停用时间。新建停用账号及启用转停用时写数据库当前时间，停用转启用时清空，状态未变时保留原值。按 disabled_at 的北京时间日期分段，停用前正常统计，停用当天及之后整日不纳入；既有 disabled_at IS NULL 的停用账号仍整段不纳入；重新启用清空后按现有事实重算。团队工作台 anomalies 保留停用前历史，在职人数、打卡/请假事实和进行中待办仍只统计在职。未来不判定，当天未到应下班时间不判缺卡或早退。全天请假免卡，两份半天请假可合并；上午请假上班基准13:00，下午请假下班基准13:00。上午请假须覆盖到13:00，标准09:00–13:00不因自定义上班时间漏判；下午请假须从不晚于13:00覆盖到 min(work_end,18:00)，兼容17:30和18:30下班规则。半天请假与另半天出差/外出共存仍计0.5天请假并保留field，有任意有效卡判FIELD，无卡判MISSING。出差/外出日有任意正常或外勤卡即外勤出勤，否则缺外勤卡。普通日不足两卡记一次缺卡，单卡按离上下班哪端近判断缺另一端；两卡及以上取首末次，补卡等同有效打卡。09:05在宽限内，09:06记迟到6分钟（分钟为距离标准时间的完整分钟数，非扣除宽限后的分钟）；早退同理。应出勤含请假日和进行中的今天，正常天数不含单独列出的请假/外勤/异常。异常计数按人日，一天两端都缺也只记一次缺卡。请假只在纳入规则的非休息日计入月度规则报表，与旧工作台自然日请假事实统计并存。

### 月度 CSV

- ADMIN `GET /api/admin/exports/attendance-monthly.csv?month=YYYY-MM&departmentId=&detail=false`：工号、姓名、部门、应出勤、正常、迟到次数、迟到分钟、早退次数、早退分钟、缺卡次数、请假天数、外勤天数。`detail=true` 改为工号/姓名/部门/日期/状态/首次/末次/迟到分钟/早退分钟/缺卡说明/关联单号。
- ADMIN 或 FINANCE `GET /api/admin/exports/expenses-monthly.csv?month=YYYY-MM&departmentId=&status=&basis=created`：单号、申请人、部门、提交时间、金额、明细条数、用途摘要、审批状态、支付状态、支付时间，末尾金额合计。默认按提交时间 created_at；`basis=approved` 仅取当前已批准单据、以最后一个已批准步骤的 acted_at 归月。管理员可导出全部状态、全部部门；非管理员财务范围为全部已批准报销，加按普通请求权限可见的其他报销（本人、审批链指派/实际处理人、改派前后审批人、抄送人）。状态筛选仅在此可见范围内生效；详情、列表与导出共用同一规则。
- 原 `requests.csv` 新增可选 `from/to`，按提交日期含首尾筛选，原参数语义不变。
- UTF-8 BOM，CRLF，所有单元格带双引号并转义；去掉前导空白后以 `= + - @` 开头或以控制换行/制表开头的单元格加单引号。超过10000行400「导出超过 10000 条，请缩小筛选范围」。新导出记录 `EXPORT_ATTENDANCE_MONTHLY` / `EXPORT_EXPENSES_MONTHLY` 审计；无数据仍有表头，报销仍有合计。

### 审批催办

- `POST /api/requests/{id}/urge`、`POST /api/contracts/{id}/urge`、`POST /api/assets/requests/{id}/urge`。仅发起人本人且单据 PENDING；越权403「仅发起人本人可催办」，已办结400。返回 `{message:"已催办 张三",urgedUserIds:[1],nextAvailableAt:"..."}`。各详情增加 `canUrge,lastUrgedAt,urgeAvailableAt`（冷却中 canUrge 仍表示发起人资格，客户端按可用时间禁用按钮）。
- 对当前步骤与当前改派审批人发通知，跳过停用者；合同/资产角色终审的空 approver_id 展开为在职 FINANCE/ADMIN，并排除发起人。无人可通知400「当前没有在职审批人，请联系管理员改派」。同一业务单据、版本、步骤、审批人默认120分钟内再次催办429「已于 HH:mm 催办过，请 X 分钟后再试」。同单据行锁串行处理并与审批/改派互斥，通知与催办记录同事务。
- ADMIN `GET/PUT /api/admin/urge-config`，字段 `urge_cooldown_minutes`（1–10080）、`auto_urge_enabled`、`auto_urge_hours`（1–8760，默认24）、`auto_urge_repeat_hours`（0–8760，默认0不重复）。写 `SAVE_URGE_CONFIG` 审计，既有审批阈值不变。
- 每10分钟北京时间巡检；提交/步骤推进/改派在数据库同事务更新 activated_at。迁移按单据创建、上一步处理和最近改派时间安全回填。全局事务 advisory lock 防多实例重复；按业务、版本、步骤、审批人、activated_at 激活周期查询 AUTO 记录去重。配置重复间隔大于0时距离上次自动催办达到间隔再发送。手动和自动记录均参与手动冷却。自动发送前也检查本激活周期最近一次任意模式催办，仍在 urge_cooldown_minutes 内则本轮跳过，冷却结束且满足 AUTO 去重/重复间隔后可发送；改派后的新周期不受旧周期冷却影响。已办结/撤回不发，停用审批人不发，不额外通知发起人。
- 所有催办仅调用现有 NotificationService.write，写消息中心并发布 Created → PushDelivery → Android FCM。真实设备到达仍需部署和有效FCM凭据。

### 常用模板与复制

本人 `GET /api/request-templates?kind=`；`POST /api/request-templates`、`PUT /api/request-templates/{id}` 接收 `{name,kind,payload}`，`DELETE /api/request-templates/{id}`。仅 LEAVE/TRAVEL/OUTING/EXPENSE；名称最多60字、本人唯一、每人最多30个，冲突或上限400中文提示，跨用户403。写操作审计。payload白名单：typeId、reason、items[{description,amount}]、ccUserIds、amount、startTime/endTime半天选项、durationDays；具体日期、附件、审批字段全部丢弃。模板列表读取也剔除后来停用的假种/抄送人并返回 warnings。

`GET /api/requests/{id}/copy` 仅发起人403限制，返回 `{kind,payload,warnings}`；不返回具体日期、附件、状态和审批记录，停用假种/抄送人剔除并提示。客户端必须重新选日期，最终提交仍通过原申请接口完整校验。加班/补卡暂不支持模板或复制（400）。

### 拆分计数与附件

`GET /api/messages/summary` 保留原 pending（通用审批+合同审批+资产审批+本人未完成任务），新增 `approvalsRequests,approvalsContracts,approvalsAssets,tasks,payments`。payments 仅ADMIN/FINANCE可见当前已批准未支付报销数量，其他为0。审批与各业务待办过滤共用口径，合同含空审批人的角色终审；notifications/announcements不变。`GET /api/messages?category=pending&itemType=REQUEST` 可只看通用申请；itemType也支持CONTRACT/ASSET/TASK，不传仍返回原混合列表。`category=notifications&unread=true` 仅未读。`GET /api/tasks?scope=assigned&status=ACTIVE` 精确对应 tasks 计数（TODO/IN_PROGRESS）。网页「全部待办」保留混合入口，「待我审批」为通用申请列表。

网页导航数字0隐藏、超过99显示99+，点击数字进入对应待审或未读子列表。消息中心导航显示未读系统通知数（公告另列）；合计展示拆为待审批与待办任务。登录、导航、业务写操作成功、焦点/可见恢复刷新，每60秒刷新，隐藏页面暂停。

所有网页附件上传共用压缩：仅JPEG/PNG，超过1.5MB或长边超过2560px时等比缩至最长边2560，JPEG质量0.82，超过2MB依次降至0.74/0.66/0.60；透明PNG铺白底。若结果更大保留原文件。扩展名与实际MIME一致。小图片/PDF字节不变；压缩后再校验10MB「每份最多 10MB」。Flutter使用纯Dart image包、compute后台isolate实现相同策略，无新增平台配置。服务端10MB和魔数校验保持不变。

### 仅 dev 的验收辅助

原模拟打卡 `/api/admin/dev/punch` 新增可选 `at`（北京时间、不允许未来）；仅 dev ADMIN，可以构造历史测试卡；不影响员工打卡。`POST /api/admin/dev/run-auto-urge` 无body执行当前巡检；可传 `{requestId,now:"ISO时刻"}` 仅对指定测试申请用模拟时刻巡检，便于验证24小时边界和去重，生产profile无此接口。

CSV 单元格统一双引号包裹并转义双引号；原串以制表/回车/换行开头，或去除前导空白后以 `= + - @`（含全角 `＝ ＋ － ＠`）开头时前置单引号。null 输出空单元格。数值沿用既有文本输出规则，负值也防护，不引入数值类型旁路；BOM、CRLF、中文标签与审计保持。
