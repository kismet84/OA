# Mac / Caddy / Cloudflare 出口 IP 配置

正式链路：手机 → Cloudflare → cloudflared → Caddy `127.0.0.1:8088` → 后端 `127.0.0.1:8080`，公网地址为 `https://oa.bzywc.cn`。本目录是后续人工应用到 Mac 的样例，不会自动修改或部署到 Mac。

## 配置

1. 将 [Caddyfile](Caddyfile) 的 API 代理配置合入 Mac 的 `~/oa-system/Caddyfile`；完整样例同时包含管理后台和 `/staff/` 的 SPA 回退。静态目录按实际安装位置调整。Caddy 必须只绑定 `127.0.0.1`，cloudflared 的 origin 保持 `http://127.0.0.1:8088`。
2. 在 Mac 的 `~/oa-system/bin/start-backend.sh` 中，加载原有环境之后、执行 `java` 之前加入以下导出（保留原有数据库、Redis、上传目录、profile 和 JVM 参数）：

   ```bash
   export SERVER_ADDRESS=127.0.0.1
   export TRUSTED_PROXIES=127.0.0.1
   ```

   这是 Mac 生产代理专用设置；本地 dev 默认保持 `TRUSTED_PROXIES` 为空，不要加入 `scripts/dev-up.sh`。
3. 后续人工在 Mac 检查配置并重载 Caddy、重启后端：

   ```bash
   ~/oa-system/bin/caddy validate --config ~/oa-system/Caddyfile --adapter caddyfile
   ~/oa-system/bin/caddy reload --config ~/oa-system/Caddyfile --adapter caddyfile
   launchctl kickstart -k gui/$(id -u)/com.company.oa.backend
   ```

## 信任边界与验收

Caddy 用 Cloudflare 覆盖写入的 `CF-Connecting-IP` **覆盖**上游 `X-OA-Client-IP`（[Caddy 官方 header_up 说明](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers)）。后端仅当连接来源属于 `TRUSTED_PROXIES` 时读取此专用头，并继续执行合法 IP 校验和规范化；其它来源忽略此头，始终不信任 `X-Forwarded-For` 链。这个配置的信任边界包含本机进程，因此 Caddy 和后端都不能暴露给外部直连，也不能让其它入口绕过 Cloudflare 注入 `CF-Connecting-IP`。

- 通过公网完成有效定位后提交打卡，记录的 `exit_ip` 应是手机公网出口，而非 `127.0.0.1`。正常打卡仍须由管理员在生产库登记真实办公出口 IP 或 SSID；本样例不登记任何网络。
- 本机直连 Caddy、不带 `CF-Connecting-IP`，即使伪造 `X-OA-Client-IP` 或 `X-Forwarded-For`，打卡也应返回 400「可信反向代理必须传递真实客户端 IP」。无效的 CF IP 也应被拒绝，不能回退为 loopback。
- 健康检查 `GET /api/health` 不依赖打卡 IP 头，可继续从本机调用。
- 在默认 dev 环境运行 `scripts/verify-mvp.py`，防伪造头用例应继续返回 400。受信代理路径另由 `PunchControllerTest` 覆盖；不要为跑 dev 验收而开启 loopback 信任。

登录与打卡共用可信代理客户端 IP 解析：后端仅在来源属于 TRUSTED_PROXIES 时接受本代理覆盖的 X-OA-Client-IP，不直接读取 CF-Connecting-IP 或 X-Forwarded-For。登录真实 IP 失败上限100次/15分钟，成功不计数；账号规范名与用户ID均在5次失败后锁定，工号/手机号共享用户计数。专用头缺失或非法时登录告警并退回独立 proxy-unknown 桶（1000次失败/15分钟），打卡仍返回400；应检查代理头配置以恢复真实 IP 隔离。

## 浏览器安全响应头（仓库样例）

本轮只修改仓库样例，未应用、未部署 Mac，现网 Caddy 响应头及 Cloudflare 配置未核实。只有后续部署后才会对 Mac 生效。

顶层 header 对静态页面和 API 统一设置 nosniff、DENY、same-origin 及与 Nginx 一致的 CSP；保留图片 data:/blob: 和 iframe blob: 供 PDF、图片、水印预览使用。这里的监听为本机 HTTP，不发送 HSTS；公网 HTTPS 应由 Cloudflare 配置 `Strict-Transport-Security: max-age=31536000`，部署验收须核对实际公网响应，不能将样例检查当作现网验证。

本地执行 `python3 scripts/verify-review-csp.py` 检查两份仓库配置，无需连接生产环境。
