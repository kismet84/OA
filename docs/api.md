# OA API 约定（含二期加班与补卡）

所有接口在 `/api` 下。除 `POST /auth/login`、`GET /health` 外均需要 `Authorization: Bearer <token>`。开发地址 `http://127.0.0.1:8080/api`，正式环境通过 HTTPS 反代同源访问。JSON 输入使用 camelCase，查询结果保留数据库 snake_case；日期使用上海时区。错误返回 `{"message":"中文原因"}`，400 参数/业务规则，401 未登录/失效，403 无权，404 不存在，409 数据冲突，429 登录限流。

## 身份与权限

- `POST /auth/login`：`{"login":"工号或手机号","password":"密码","deviceName":"员工手机","platform":"Android"}` → `token`、`user`。会话有效期 8 小时，不滑动续期。`deviceName` / `platform` 可选，最多100 / 30字；未填显示「未命名设备」/「未知平台」，兼容既有客户端。
- `GET /auth/me` → `user: {id,name,roles,mustChange,version}`、`development`。
- `POST /auth/password`：`{"oldPassword":"原密码","newPassword":"至少10位新密码"}`。密码最多 72 个 UTF-8 字节，修改后所有旧会话失效，须重新登录。
- `POST /auth/logout`：销毁当前会话。
- 首次登录只允许查自身、改密码及退出。普通员工只看本人数据；审批人额外可查看分配给自己的单据；抄送人可查看抄送单据及附件，公告接收人可查看未撤回公告；财务额外可查看全部已批准报销及其票据，供人工支付登记；管理员可查询全员、代提交和处理审批，但不可自批。
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
- 财务可查询全部已批准报销（包括未经过财务二级审批的低额报销）及其票据，不能因此查看无关的未批准报销或其他类型单据；原有本人和审批链权限保持。后台财务账号显示审批单据和消息中心入口，无组织维护或全员导出权限。

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

正式接口不接受用户填写出口 IP，仅使用连接来源；若连接来源恰好在 `TRUSTED_PROXIES` 中，则读取由 Nginx **覆盖写入**的 `X-OA-Client-IP`。不要信任任意来源的转发头。SSID 是客户端声明，不能证明设备真实连入指定 Wi-Fi；需要强网络校验时只登记办公出口 IP，后续 App 再补充可信网络采集。

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
- `GET /messages/summary` → `{pending:当前待办数,notifications:未读系统通知数,announcements:未读且未撤回公告数}`。三个字段各自为数字，统计全部记录，不受 200 条分页限制，可复用于后续 App 红点；没有自动推送或轮询。
- 原 `GET /notifications` 继续返回本人最新 200 条；`POST /notifications/{id}/read` 保留首次已读时间，无权或不存在返回 403。消息中心使用同一张表，状态同步。

所有新接口均需登录及完成首次改密；停用账号原有会话立即失效。业务时间仍为 Asia/Shanghai。本期不提供设备登记或推送发送接口，不连接 APNs、FCM 或厂商通道，无证书也可完整查询、阅读和下载站内消息。


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

迁移 `V4__employee_profile_and_sessions.sql` 仅新增字段和会话表，旧员工资料字段为空；升级前仅存在 Redis 的会话需重新登录登记设备。撤销和过期会话留在表中供审计关联，本期未实现定期清理任务。App 不接入真实推送，消息通过现有消息接口刷新。


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
- 创建邀请、改期/换会议室、取消、新增/移出参会人均复用系统通知。组织者不重复收到参会邀请；通知含可空 `meeting_id`，旧 `request_id` 和旧通知兼容。Flutter、后台和 `/staff/` 消息中心可打开关联会议，无 APNs/FCM/厂商推送。
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
- 未登录401，越权403，参数/状态错误400，不存在404；错误信息中文。任务没有附件、子任务、循环任务、项目看板或甘特图；不接真实推送、不做新工作台汇总，文档资料为下文独立模块。

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
- 发布、重新上架给当前接收人发系统通知；修改已发布文档只通知新进入范围的人，操作者本人不重复接收。修改下架文档不发通知，待重新上架统一通知。增加 `document_id` 的系统通知可从三端进入对应文档；已下架/失去权限则显示中文原因；不进入审批待办，不新增推送或工作台汇总。
- 首次**成功生成预览页**记已读，详情、预览元数据和下载不记已读；修改和重新上架不清除首次时间。写操作及预览使用事务行锁，下架与预览授权顺序一致。审计复用原表：`PUBLISH_DOCUMENT`、`UPDATE_DOCUMENT`、`WITHDRAW_DOCUMENT`、`REPUBLISH_DOCUMENT`。
- 未登录401，越权403，输入/页码/文件格式错误400，不存在404；错误为中文。普通员工不能创建上传公司文档或指定个人发送；不做网盘、协同编辑、Wiki、多级目录、权限矩阵、在线批注或全文检索引擎。

验收：`python3 scripts/verify-phase2-docs.py`（需Python Pillow，真实HTTP与像素水印检查）、`npm --prefix admin run test:e2e -- documents.spec.ts`、Maven全部测试、`npm --prefix admin run build`、`cd mobile && flutter analyze && flutter test`。新浏览器用例走管理员发布、下级员工可见/水印预览/下载、范围外不可见、禁下载、下架消失、通知失效提示、恢复和回执；Flutter覆盖逐页预览、下载入口、二进制403及通知失去权限。仅本地验证，不涉及正式签名、IPA或真实推送。

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

批准、驳回、发放、拒发和确认归还向申请人写入原notifications系统通知，以asset_request_id关联，三端可打开详情；无真实推送。所有维护和状态流转写原audit_logs，库存调整记录数量/状态及原因。工作台六类审批统计继续仅取原requests，不计资产，全部考勤/审批汇总规则不变。

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

每天上海08:00运行提醒，并在登录用户读取`GET /messages/summary`时补发。只处理TO_ARCHIVE/ACTIVE，expiry_date落在今天至今天+30或今天+7日（含首尾），按合同与节点唯一约束各发一次；服务停机跨过精确节点后，只要仍未到期即可补发，7天内首次补发可同时收到两条。已到期/终止不发，长期不发；同一合同修改到期日后不重置已发送节点。使用原notifications，contract_id关联，经办人收取，不接真实推送。

提交、分级通过、驳回、撤回、归档、终止及到期提醒向经办人写系统通知；提交/中间通过还通知下一审批人，终审组通知启用的ADMIN/FINANCE（排除申请人）。消息待办`GET /messages?category=pending`合并合同，`item_type=CONTRACT`、title为合同名称；三端显示「合同」并进入详情审批。`GET /messages/summary.pending`在原六类审批、任务及资产之上按合同加一，不增加消息大类。工作台统计仍仅取原requests六类，合同不参与审批时效。登记、删除、提交、审批、撤回、归档、终止、附件读取和导出均复用audit_logs。

### 本地验收

```bash
source scripts/env.sh
python3 scripts/verify-phase3-contracts.py
npm --prefix admin run test:e2e -- contracts.spec.ts
```

HTTP覆盖未登录401、越权403及列表隔离、编号409、分页、导出BOM与权限、50000边界、超阈值、同人去重、审批中锁定、驳回/撤回修改重提、归档、附件权限/跨业务隔离、到期与提醒两节点各一次、审计、原待办合并及六类工作台不变。浏览器覆盖员工移动网页起草提交、上级待办驳回、修改重提、上级/负责人/财务审批、盖章归档、管理员筛选导出和原待办数字保留。

Flutter常规`flutter analyze && flutter test`覆盖中文合同表单、必填/冲突保留数据、长期及无固定金额、详情归档和合同待办导航。真实接口验收先运行上述HTTP脚本，从`.runtime/contracts-http-result.json`读取prefix，在mobile目录设置`OA_CONTRACT_PREFIX=<prefix>`后执行`flutter test test/contracts_live_test.dart`，使用独立测试账号完成登记、驳回重提、三级审批、附件归档下载与终止；未设置变量时跳过该真实接口用例。另保留Maven全量、Vue构建、既有HTTP与浏览器回归通过记录，未受后续改动影响的不重复运行。不生成APK、iOS签名或IPA，不启动cloudflared/watchdog/8088 nginx。
