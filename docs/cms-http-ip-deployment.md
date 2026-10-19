# 公网入口：HTTP + IP（域名暂停解析）

日期：2026-10-18。`aihyb.com` 因备案原因暂停解析，公网入口改为纯 HTTP + IP，不再使用域名，也不签发证书。当前发布的是既有 Halo PoC、Earth 主题和虚构演示内容，用于查看四模块及媒体效果；不是生产性能验收。

| 用途 | 地址 |
|---|---|
| 前台（公网） | [http://5.231.253.4/](http://5.231.253.4/) |
| 四模块 | [http://5.231.253.4/aihyb](http://5.231.253.4/aihyb) |
| 搜索 | [http://5.231.253.4/search?keyword=价格](http://5.231.253.4/search?keyword=%E4%BB%B7%E6%A0%BC) |
| 后台（仅隧道） | http://127.0.0.1:18090/console |
| 原生文章/审核入口（仅隧道） | http://127.0.0.1:18090/console/posts |

后台账号沿用现有账号，密码不写入本报告。域名阶段的原始记录保留在[独立文档](./cms-domain-deployment.md)，仅作追溯。

## 为什么后台只走隧道

纯 HTTP 下浏览器与服务器之间没有加密：登录口令、Session Cookie 和后台接口内容都会明文过公网。所以公网入口只开放**只读前台**，`/console`、`/login`、`/logout`、`/signup`、`/uc`、`/password-reset` 及账号类 API 一律返回 403 并给出隧道提示。

例外是两个匿名自检接口 `/apis/api.console.halo.run/v1alpha1/users/-` 和 `.../users/-/permissions`：主题的点赞/评论组件（`main-*.js`、`PluginCommentWidget`）在公开页会调用它们，只返回空权限，必须留在公网，否则组件报错。

## 部署方式

沿用 Docker Compose，目录 `/opt/aihyb-halo-poc`、项目名 `aihyb-halo-poc`、`COMPOSE_FILE` 已包含 compose.yaml、compose.media.yaml、compose.public.yaml。Caddy 仍用固定 digest 镜像 `caddy@sha256:d8542f48…`，仍是 host 网络、只读根文件系统、关闭 admin 接口；数据库没有映射主机端口，`net.ipv4.ip_forward` 保持 0。

变化点只有四处：

- `public/Caddyfile`：站点从 `aihyb.com{…}` 改为 `:80{…}`，并设置 `auto_https off`（不申请证书、不监听 443）；保留压缩、安全响应头和 `actuator`/OpenAPI/Swagger 的 404 拦截，新增后台路径 403。
- `compose.public.yaml`：`HALO_EXTERNAL_URL` 改为 `http://5.231.253.4`。
- Halo 数据库 `system` ConfigMap 的 `basic.externalUrl` 改为 `http://5.231.253.4`。**这一处必须改**：Halo 2.26.1 优先读取数据库设置，只改容器变量会让 canonical、Feed 和站点地图继续指向旧域名。[对应版本实现](https://github.com/halo-dev/halo/blob/v2.26.1/application/src/main/java/run/halo/app/infra/SystemConfigFirstExternalUrlSupplier.java)
- 证书与私钥仍保留在 `data/caddy/data`（未删除、已纳入备份），只是不再对外服务；443 已不再监听。域名恢复后改回 HTTPS 见文末步骤。

业务数据没有结构变化：11 个稿件条目、250 份不可变修订、170 份正文快照在切换前后完全一致；稿件条目的 `public_url` 是相对路径，所以无需批量改写正文或 JSON。

## 数据实际存在哪里

| 主机目录 | 容器路径/用途 | 备份方式 |
|---|---|---|
| `/opt/aihyb-halo-poc/data/halo` | Halo `/root/.halo2`；插件、主题、应用文件、部分本地附件与日志 | 备份工作目录，与数据库使用同一备份窗口 |
| `/opt/aihyb-halo-poc/data/postgres` | PostgreSQL `/var/lib/postgresql/data`；文章、完整稿件、修订、审核与发布状态、配置 | 使用 pg_dump；不要只复制正在运行的原始目录 |
| `/opt/aihyb-halo-poc/data/caddy/data` | Caddy `/data`；旧域名证书、私钥与续期状态（当前不再服务） | 纳入私密文件备份 |
| `/opt/aihyb-halo-poc/data/caddy/config` | Caddy `/config`；运行配置状态 | 随配置一起备份 |
| `.env`、`secrets`、Compose 文件、`public/Caddyfile` | 运行凭据、存储配置、部署配置 | 私密备份，不提交公共仓库 |
| 腾讯云 COS | 图片、附件与视频实际对象 | 与服务器备份分开管理；不在数据库或本地 tar 里 |

容器可重建，以上宿主机目录会保留数据。**Docker 的数据映射提供持久化，不等于已经有独立备份。**

切换前的入口文件与设置保存在 `backups/ip-http-switch/`（含 `.env`，权限 600）与 `secrets/ip-http-original-system.json`；切换前的业务基线在 `secrets/ip-http-baseline.json`。已有 `backup.sh` 仍会短暂停止本项目 Halo 并生成数据库逻辑备份、Halo 目录归档、Caddy 文件归档，输出到 `/opt/aihyb-halo-poc/backups/<UTC时间>`；未新增定时备份任务，也未做完整恢复演练。

## 验证与证据

- [入口检查](../demo/halo-poc/evidence/ip-http-verification.json)：首页、四模块、5 篇归档页、13 个本地静态资源、搜索（含中文关键词）、8 条 Feed 与站点地图均 200，canonical/Feed 已全部指向 `http://5.231.253.4`；后台与账号类 12 条路由 403；`actuator`、OpenAPI、Swagger 404；两个匿名自检接口保持 200；媒体哈希与 Range 206 通过；443 未监听。
- [外部可达性](../demo/halo-poc/evidence/ip-http-external-reachability.json)：服务器侧 443 直连被拒、只监听 80；第三方节点（德国、匈牙利、印度、葡萄牙）取 `http://5.231.253.4/` 均 200，443 一律 `refused`。本机自己的 TCP/TLS 探测不可信：本机走代理，`aihyb.com` 与 443 都会被代理用 `198.18.x` 假地址应答，所以端口结论只采服务器侧与第三方节点。
- [隧道后台检查](../demo/halo-poc/evidence/ip-http-login.json)：仅本机回环登录、只读草稿核对通过；Session 为 HttpOnly，因入口是 `http://127.0.0.1` 而无 Secure 标志（流量在 SSH 通道内，不出公网）。
- [业务数据对照](../demo/halo-poc/evidence/ip-http-persistence.json)：11 个条目 / 250 份修订 / 170 份正文快照与切换前一致。
- [运行状态](../demo/halo-poc/evidence/ip-http-artifacts.json)：固定 Caddy 镜像、host 网络、只读根、`auto_https` 关闭、仅监听 80、forwarding=0、证书保留但不再服务。
- [主题封装变更](../demo/halo-poc/evidence/theme-adapters.json)：本次只改两个主题的 `templates/search.html`（有原哈希保护与备份）。
- 截图：[公网首页](../demo/halo-poc/evidence/screenshots/ip-http-home-20261018.png)、[搜索“价格”](../demo/halo-poc/evidence/screenshots/ip-http-search-20261018.png)、[公网访问后台时的提示页](../demo/halo-poc/evidence/screenshots/ip-http-console-blocked-20261018.png)。

## 顺带修复：页头搜索按钮的 500

验证时发现点页头搜索按钮（裸 `/search`，无 keyword）返回 500，日志为 `SpelEvaluationException: The collection has '0' elements, index '0' is invalid`。原因是 Thymeleaf 中 `param.keyword` 缺失时是**空列表而非 null**，模板里 `param.keyword[0]` 直接越界。已在 `themes/shared/search.html` 改为先判空列表，并按原有哈希保护流程经 `deploy-theme-adapters.py` 部署，两个主题都已生效；现在裸 `/search` 与带关键词搜索都返回 200。

## 已知限制

- 纯 HTTP 无加密：任何人不借助中间人也能看到正文与访问路径；后台内容因走隧道不受影响。**不要把真实账号、真实用户数据放到公网入口上**，测试期按此约束使用。
- 没有域名意味着没有可信证书，也没有 CDN 与 ICP 备案；正式上线仍需备案后的域名 + HTTPS。
- IP 直连无法用 SNI 区分站点，后续若在同一 IP 上跑多个站点需要重新规划入口。
- 单文件媒体限制仍为 16 MiB；搜索为小规模验证，不是负载结论。
- 从中国大陆访问 `5.231.253.4:80` 的稳定性未测：第三方节点里 `us2` 一条路径就出现超时，说明部分线路上 80 端口本身也可能被中间网络干扰。这类问题不是服务器 listener 故障，但会真实影响用户，正式部署前需要备案后的国内线路复核。
- 本机（开发机）的网络经过代理，会把外部地址解析成 `198.18.x` 假 IP，**在本机用 ping/端口探测判断服务器状态会得出错误结论**；排查请用服务器侧 `ss`/`curl` 或第三方节点。

## 域名恢复后怎么切回

1. 域名重新解析到 `5.231.253.4`，且备案通过。
2. `public/Caddyfile` 把 `:80` 段改回 `站点域名 { … }`，删掉 `auto_https off`；后台 403 规则可以整段删除（HTTPS 下可以重新开放 `/console`）。
3. `compose.public.yaml` 的 `HALO_EXTERNAL_URL` 与数据库 `system.basic.externalUrl` 都改回 `https://` 域名，两处必须一致。
4. 重启 halo 与 caddy；`verify-ip-http.py` 会因 origin 不再是 IP 而失败，这正是切换信号，改用域名阶段的检查脚本或按新 origin 调整常量。
5. `data/caddy/data` 里的证书会复用/自动续期，不需要重新申请。

原域名阶段的完整记录见[cms-domain-deployment.md](./cms-domain-deployment.md)，仅作追溯。
