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
