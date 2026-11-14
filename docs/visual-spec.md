# OA 三端视觉规范

## 基准与现状

管理台是基准：保留手工打磨的深青绿 `#17635d`、深色侧栏、白色卡片、紧凑表格与中文标签。员工网页复用相同的 CSS 令牌；App 从 `#176b64` 种子主题改为明确的 ColorScheme，避免 Material 自动推导出另一套色值。移动端触控采用 App 的 48 逻辑像素标准，网页手机同步采用。只调整显示，不改变流程、接口或权限。

改前：管理台正文 `#233d3c`，背景 `#f3f6f6`，边框多种相近灰绿，次要文字 `#718581` 等偏浅；状态待审批 `#997219/#fff3db`、已批准 `#357253/#e5f2ea`、已驳回 `#aa5950/#fbeae8`，撤回与撤销依赖默认灰色。网页字号 10–50px、间距非统一刻度、圆角 4–20px。员工网页共用样式，但小按钮与双列表单的触控不够宽松。App 背景 `#f5f7f9`，种子色 `#176b64`，表面和状态依赖默认主题，卡片带色偏、按钮偏胶囊，列表状态主要是纯文本。

## 唯一数据源与维护

`docs/design-tokens.json` 是唯一数据源。CSS 和 Dart 的生成区由 `python3 scripts/verify-visual-tokens.py --write` 生成；Dart 生成区外的组件主题引用这些常量。新增或调整时：修改 JSON → 运行 `--write` 同步两端 → `dart format mobile/lib/theme.dart` → `python3 scripts/verify-visual-tokens.py` → 运行网页与 Flutter 验收并复查截图。生成器不依赖第三方库。显示文案从 JSON 的 status/terms 取值或同名常量引用；业务枚举和接口字段不随文案修改。

校验器逐值检查生成文件（包含状态、标签、字号、字重、行高、间距、圆角、阴影、用语和触控尺寸），计算 88 组 WCAG sRGB 对比度，检查 CSS 引用是否已定义，并扫描两端源码中的颜色字面量；仅白色画布底色例外。正文与状态文字至少 4.5:1；焦点、输入和按钮边界至少 3:1。卡片分隔边框是装饰线，不承担控件识别；控件使用 borderStrong。

## 颜色

| Token | 色值 | 用途 |
| --- | --- | --- |
| primary | `#17635d` | 主按钮、链接、图标 |
| primaryHover | `#104e49` | 悬停 |
| primaryPressed | `#0d423e` | 按下 |
| onPrimary | `#ffffff` | 主按钮文字 |
| primarySoft | `#e8f2ef` | 选中浅底 |
| bg | `#f3f6f6` | 页面背景 |
| surface | `#ffffff` | 卡片、输入、弹窗 |
| surfaceMuted | `#f0f4f3` | 筛选区、表头、禁用底 |
| border | `#d4dfdc` | 装饰分隔线、卡片边框 |
| borderStrong | `#718581` | 控件边界 |
| text | `#233d3c` | 正文、标题 |
| textSecondary | `#48635e` | 辅助正文 |
| textMuted | `#586f69` | 说明、空状态 |
| link | `#17635d` | 链接 |
| focus | `#17635d` | 键盘焦点 |
| danger | `#a33232` | 危险、错误 |
| dangerSoft | `#fbeae8` | 错误浅底 |
| warning | `#795507` | 待处理、警告 |
| warningSoft | `#fff3db` | 警告浅底 |
| success | `#276447` | 成功 |
| successSoft | `#e5f2ea` | 成功浅底 |
| info | `#285e83` | 信息、进行中 |
| infoSoft | `#e8f1f8` | 信息浅底 |
| sidebarBg | `#183e3c` | 侧栏、深色提示 |
| sidebarText | `#d7e6e0` | 侧栏正文 |
| sidebarMuted | `#b9d0c8` | 侧栏辅助字 |
| sidebarActive | `#2e5d55` | 侧栏选中 |
| sidebarHover | `#28524c` | 侧栏悬停 |
| overlay | `#102a2860` | 弹窗遮罩（带透明度） |
| decoration | `#41716b` | 登录装饰线 |

## 状态色

| 状态 key | 显示名 | 前景 fg | 背景 bg | 边框 |
| --- | --- | --- | --- | --- |
| pending | 待审批 | `#795507` | `#fff3db` | `#795507` |
| approved | 已批准 | `#276447` | `#e5f2ea` | `#276447` |
| rejected | 已驳回 | `#a33232` | `#fbeae8` | `#a33232` |
| withdrawn | 已撤回 | `#48635e` | `#f0f4f3` | `#48635e` |
| cancelled | 已撤销 | `#48635e` | `#f0f4f3` | `#48635e` |
| paid | 已支付 | `#276447` | `#e5f2ea` | `#276447` |
| unpaid | 待支付 | `#795507` | `#fff3db` | `#795507` |
| ready | 待发放 | `#285e83` | `#e8f1f8` | `#285e83` |
| issued | 已发放 | `#276447` | `#e5f2ea` | `#276447` |
| in_use | 使用中 | `#285e83` | `#e8f1f8` | `#285e83` |
| returned | 已归还 | `#48635e` | `#f0f4f3` | `#48635e` |
| overdue | 已逾期 | `#a33232` | `#fbeae8` | `#a33232` |
| todo | 待处理 | `#795507` | `#fff3db` | `#795507` |
| in_progress | 进行中 | `#285e83` | `#e8f1f8` | `#285e83` |
| completed | 已完成 | `#276447` | `#e5f2ea` | `#276447` |
| draft | 草稿 | `#48635e` | `#f0f4f3` | `#48635e` |
| to_archive | 待归档 | `#795507` | `#fff3db` | `#795507` |
| active | 履行中 | `#276447` | `#e5f2ea` | `#276447` |
| expired | 已到期 | `#a33232` | `#fbeae8` | `#a33232` |
| terminated | 已终止 | `#48635e` | `#f0f4f3` | `#48635e` |
| waiting | 待到访 | `#795507` | `#fff3db` | `#795507` |
| arrived | 已到访 | `#276447` | `#e5f2ea` | `#276447` |
| left | 已离开 | `#48635e` | `#f0f4f3` | `#48635e` |
| available | 在库 | `#276447` | `#e5f2ea` | `#276447` |
| assigned | 已领用 | `#285e83` | `#e8f1f8` | `#285e83` |
| repair | 维修中 | `#795507` | `#fff3db` | `#795507` |
| scrapped | 已报废 | `#48635e` | `#f0f4f3` | `#48635e` |

审批的 WITHDRAWN 为待批准时撤回，CANCELLED 为批准后的撤销，二者保留独立用语。领域同名枚举保留含义：任务/访客 CANCELLED 显示「已取消」，访客 EXPIRED 显示「已过期」，合同 EXPIRED 显示「已到期」；三端同领域一致，同色。会议回复 PENDING 是「待回复」，不当作审批状态。标签必须保留文字，不仅依靠颜色。

## 字体、间距与形状

中文继续使用系统字体，不加载网络字体。网页：`'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif`；Flutter：Android 系统默认 Roboto，并回退 sans-serif、PingFang SC、Microsoft YaHei；不打包字体。系统字重实际呈现可随平台略有差别。

| 项目 | 刻度 |
| --- | --- |
| 字号 | xs 12px / sm 13px / body 14px / lg 16px / title 18px / heading 22px / display 28px / hero 48px / metric 36px |
| 字重 | 400 正文 / 500 标签 / 600 标题和按钮 / 700 强调 |
| 行高 | 正文 1.6 / 标题、状态标签 1.4 |
| 间距 | 0–100，每 4px 一档；常用 4 / 8 / 12 / 16 / 20 / 24 / 28 / 32 |
| 圆角 | sm 4 / md 8 / lg 12 / pill 999 |
| 阴影 sm | 0 3px 12px 0 #183e3c08 |
| 阴影 md | 0 20px 60px 0 #183e3c20 |
| 控件 | 网页按钮最小 40px，输入最小 44px；员工网页手机、App 触控最小 48px |

## 组件

- 主按钮：primary/onPrimary，md 圆角；悬停 primaryHover，按下 primaryPressed。次按钮：surface 底、primary 或 textSecondary 字、borderStrong 边。危险操作：danger 文字并配明确动作名。禁用：surfaceMuted/textMuted，不降低整块透明度。
- 卡片：surface、border 装饰边、lg 圆角，网页 sm 阴影；App 密集列表采用无投影白卡，避免层叠阴影影响阅读。弹窗采用 lg 圆角，网页 md 阴影与 overlay 遮罩。
- 状态：网页 `.badge.<lowercase_enum>`（兼容 `.status-tag.status-*`），统一 fg/bg/border；App 使用 `StatusChip` / `statusColors()`。详情和列表沿用原有文本信息，在审批列表及各业务状态旁增加标签。
- 表单：surface 底、borderStrong、md 圆角；focus 2px 主色框；错误使用 danger/dangerSoft，保留完整错误信息。中文标签可换行；手机双列变单列，不裁剪输入。
- 表格/列表：表头 surfaceMuted、辅助字 textMuted，行以 border 分隔；网页长表格在容器内横向滚动。App 使用统一 ListTile 内边距与可换行副标题。
- 空状态：保留「暂无…」等具体对象名称，居中、textMuted、至少 28px 内边距；加载继续使用原有状态与指示器。
- 提示：网页错误 danger/dangerSoft，成功 success/successSoft；App 内联错误使用 colorScheme.error，SnackBar 使用 sidebarBg/sidebarText，并保持原反馈时机。不得根据颜色改变操作结果或流程。
- 导航：管理台保留单字边框图标；员工网页手机三列导航，按钮 48px 且允许换行；App 业务入口及动作图标统一 Material outlined/outline，箭头、加号、刷新等采用相应 outlined 版本。不同平台不强求同一字形，但保持细线、单色、同一语义，不使用 emoji。

## 用语统一表

| 旧叫法 | 新叫法 | 涉及端 |
| --- | --- | --- |
| FIELD 的出勤、出勤打卡、外勤出勤 | 外勤、外勤打卡 | 管理台、员工网页、App |
| 审批单据、审批与申请 | 审批与申请 | 管理台对齐 App；员工消息保持审批单业务对象 |
| 退出 | 退出登录 | 管理台，对齐员工网页、App |
| 工号 / 手机号 | 工号或手机号 | 管理台、员工网页，对齐 App |
| 合同审批中 | 待审批 | 管理台、员工网页、App |
| 已撤回 / 已撤销 | 保留二者差别 | 三端申请状态，避免混淆 |
| 我的申请、我发起的 | 保留领域与分类含义 | 资产页为我的申请，消息分类为我发起的 |
| 消息中心、系统通知、公告 | 保留入口与内容分类的层级 | 三端 |
| 资产领用 | 资产领用 | 三端保留 |

「应出勤」「出勤天数」属于考勤统计概念，保持原意。打卡提示必须保留公司网络、有效出差/外出审批、定位精度 ≤ 200 米、5 分钟内、旧客户端须升级 App 或改用员工网页等全部原义。界面词语变化不修改后端消息或推送内容。

## 可访问性与复核

键盘操作保持原有语义、role 和 aria 关联；可交互项显示 focus-visible。正文不低于 12px，常规正文 14px，移动端主要列表标题 16px。48px 是最小触控目标而非固定高度，长文字应增长高度。错误与状态必须以文本说明。截图后检查表单、弹窗、标签、长姓名和手机横向溢出；同时保留表格横向滚动。色觉差异不影响状态辨识。

## 遗留边界

后端返回和推送的「出勤」等旧用语可能继续显示在原始错误/通知文本中，本次后端零改动。真实设备的系统字体、字号缩放、定位授权提示效果须后续核实；本次只做本地网页与 Flutter 组件截图，不部署、不生成安装包。
