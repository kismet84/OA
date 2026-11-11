# 一期 API 约定

所有接口在 `/api` 下。除 `POST /auth/login`、`GET /health` 外均需要 `Authorization: Bearer <token>`。开发地址 `http://127.0.0.1:8080/api`，正式环境通过 HTTPS 反代同源访问。JSON 输入使用 camelCase，查询结果保留数据库 snake_case；日期使用上海时区。错误返回 `{"message":"中文原因"}`，400 参数/业务规则，401 未登录/失效，403 无权，404 不存在，409 数据冲突，429 登录限流。

## 身份与权限

- `POST /auth/login`：`{"login":"工号或手机号","password":"密码"}` → `token`、`user`。会话有效期 8 小时，不滑动续期。
- `GET /auth/me` → `user: {id,name,roles,mustChange,version}`、`development`。
- `POST /auth/password`：`{"oldPassword":"原密码","newPassword":"至少10位新密码"}`。密码最多 72 个 UTF-8 字节，修改后所有旧会话失效，须重新登录。
- `POST /auth/logout`：销毁当前会话。
- 首次登录只允许查自身、改密码及退出。普通员工只看本人数据；审批人额外可查看分配给自己的单据；管理员可查询全员、代提交和处理审批，但不可自批。
- 角色枚举：`EMPLOYEE` 员工、`SUPERVISOR` 直属上级、`DEPT_HEAD` 部门负责人、`ADMIN` 管理员、`FINANCE` 财务/行政。

## 管理员维护

以下路径均为 `/admin/...`，全部要求管理员；写操作记录审计，不提供硬删除员工接口。

| 路径 | 方法 | 输入 / 作用 |
| --- | --- | --- |
| `/departments` | GET / POST | 列表 / `{name,parentId?,headId?}` |
| `/departments/{id}` | PUT | 同上；拒绝部门循环，负责人须有效且具有负责人角色 |
| `/users` | GET / POST | 列表 / `{employeeNo,phone?,name,roles:[],departmentId,managerId?,enabled,password}` |
| `/users/{id}` | PUT | 同上；`password:null` 保留原密码，非空则重置并强制改密；修改后旧会话失效 |
| `/networks` | GET / POST | 列表 / `{kind:"SSID"或"IP",value}`；IP 必须是完整地址，不接受 CIDR |
| `/networks/{id}` | DELETE | 移除网络登记 |
| `/leave-types` | GET / POST | `{code,name,deductBalance,annualLimit?,proofRequired,enabled}` |
| `/leave-types/{id}` | PUT | 同上；已有 code 不可修改，预置法定假要求证明和可休天数 |
| `/balances` | GET / PUT | 查询 / `{userId,typeId,year,amount}`，设置**当前剩余**余额，而非增加额度 |
| `/workflow` | GET / PUT | `{leaveThreshold,travelThreshold,expenseThreshold,financeId?}`；GET 返回 snake_case |
| `/audit` | GET | 最近 500 条审计 |

停用前须调整该人员的在职下属、负责部门、财务配置和待审批链；防止产生无人可批的单据。已有申请可由管理员处理或撤回后调整。至少保留一个有效管理员，不能停用自身或移除自身管理员角色。

## 申请、审批和证明

- `GET /leave-types`：启用的假期类型；`GET /balances`：本人余额。
- `POST /attachments`：multipart 字段 `file`，最多 10MB，仅 PDF/PNG/JPEG，返回 `id`。附件以随机 UUID 保存在本地目录，下载须鉴权。
- `GET /attachments/{uuid}`：作者、关联申请人、审批链内人员或管理员可下载，不开放静态上传目录。
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

`kind`：`LEAVE` 请假、`TRAVEL` 出差、`OUTING` 外出、`EXPENSE` 报销。管理员可以另传 `userId` 代提交，员工不可代他人。报销传 `amount`，起止时间和 typeId 留空。请假必须传 typeId；证明必须由当前提交人上传（管理员代提交时使用管理员上传的证明）。

天数由服务端计算，不能手填：起点 09:00/13:00，终点 13:00/18:00，上午和下午各半天，按自然日计含周末；跨年必须拆单。出差可用跨日时段覆盖夜间。结束时间须在未来，允许起始已到达的申请；仍需审批通过才能出勤。待批或已批申请之间不能重叠。年假/调休余额以开始年度为准，最终批准才扣减；年度上限只统计已批准且未撤销的假期。

- `GET /requests?status=&kind=&userId=&inbox=false&offset=0`：每页最多 200 条。普通用户仅能看到本人或分配给其审批的单据。`inbox=true` 查当前轮到本人处理的待办。
- `GET /requests/{id}`：单据与 `steps` 审批链、审批意见及时间。
- `POST /requests/{id}/decision`：`{"approve":true,"comment":"同意"}`，驳回传 false 且意见必填。仅当前步骤审批人或管理员可处理；禁止自批。重复处理会拒绝。审批链和假期扣减/年度上限规则在提交时快照。
- `POST /requests/{id}/cancel`：申请人或管理员撤回待批单，或撤销尚未开始的已批单。后者返还余额并向已批人生成站内通知；已开始及已批报销不可撤销。
- `GET /notifications`：本人最新 200 条；`POST /notifications/{id}/read`：本人标记已读。

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

定位三项必须存在且合法：纬度 [-90,90]、经度 [-180,180]、精度 (0,100000] 米；时间取服务端当前上海时间，无补卡接口。`NORMAL` 要求匹配登记 SSID 或真实来源 IP。`FIELD` 要求本人有已批准的 TRAVEL/OUTING，且 `startAt <= 当前时间 < endAt`；任何地点和网络均可。请假不阻止打卡和查记录。

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
