# aihyb.com 临时预览部署

日期：2026-10-17。当前发布的是既有 Halo PoC、Earth 主题和虚构演示内容，用于查看四模块及媒体效果；不是生产性能验收。

网站：[https://aihyb.com/](https://aihyb.com/)；四模块：[https://aihyb.com/aihyb](https://aihyb.com/aihyb)；后台：[https://aihyb.com/console/](https://aihyb.com/console/)。域名直接访问，不再需要本机 SSH 隧道。后台账号沿用现有账号，密码不写入本报告。

## 部署方式

沿用 Docker Compose，目录 `/opt/aihyb-halo-poc`、项目名 `aihyb-halo-poc`。Halo、PostgreSQL、Caddy 均在独立容器中；镜像固定 digest，没有升级 Halo、数据库或业务插件。默认 Compose 文件组合已写入私密 `.env` 的 COMPOSE_FILE，包含 compose.yaml、compose.media.yaml、compose.public.yaml，后续在该目录使用普通 `docker compose` 命令即可保留媒体与域名设置。

Caddy 负责 80/443、HTTP→HTTPS、受信任证书及自动续期，并代理到 Halo 的 127.0.0.1:18090。它单独使用主机网络，以保留全局 forwarding=0；未改变既有系统服务与防火墙规则。Halo 仍仅绑定本机，PostgreSQL 没有映射主机端口。Caddy 管理接口关闭，公网拒绝 actuator、OpenAPI 和 Swagger 路径；后台及稿件接口保留 Halo 登录、角色与版本保护。

网站地址在两个地方保持一致：Compose 的 HALO_EXTERNAL_URL，以及数据库的 system ConfigMap 中 basic.externalUrl。Halo 2.26.1 优先使用系统设置中的地址；仅修改容器变量，会让模块 canonical、Feed 和站点地图仍引用旧测试地址。此次只更新该系统字段，保留其他设置，并重启自有 Halo 刷新缓存。[对应版本实现](https://github.com/halo-dev/halo/blob/v2.26.1/application/src/main/java/run/halo/app/infra/SystemConfigFirstExternalUrlSupplier.java)

## 数据实际存在哪里

| 主机目录 | 容器路径/用途 | 备份方式 |
|---|---|---|
| `/opt/aihyb-halo-poc/data/halo` | Halo `/root/.halo2`；插件、主题、应用文件、部分本地附件与日志 | 备份工作目录，与数据库使用同一备份窗口 |
| `/opt/aihyb-halo-poc/data/postgres` | PostgreSQL `/var/lib/postgresql/data`；文章、完整稿件、修订、审核与发布状态、配置 | 使用 pg_dump；不要只复制正在运行的原始目录 |
| `/opt/aihyb-halo-poc/data/caddy/data` | Caddy `/data`；证书、私钥与续期状态 | 纳入私密文件备份 |
| `/opt/aihyb-halo-poc/data/caddy/config` | Caddy `/config`；运行配置状态 | 随配置一起备份 |
| `.env`、`secrets`、Compose 文件、`public/Caddyfile` | 运行凭据、存储配置、部署配置 | 私密备份，不提交公共仓库 |
| 腾讯云 COS | 图片、附件与视频实际对象 | 与服务器备份分开管理；不在数据库或本地 tar 里 |

容器可重建，以上宿主机目录会保留数据。**Docker 的数据映射提供持久化，不等于已经有独立备份。** 稿件 JSON/Markdown 与数据库修订、云端媒体、运行配置三部分都需要保留。

已有 `backup.sh` 会短暂停止本项目 Halo，生成 PostgreSQL 逻辑备份、Halo 工作目录归档、Caddy 文件归档，保存 Compose、媒体专用出站配置和受限凭据，再启动 Halo 并检查就绪。输出目录为 `/opt/aihyb-halo-poc/backups/<UTC时间>`，生成校验和；不停止数据库、Caddy或其他原有服务。公网在 Halo 重启期间可能短暂返回 502。没有新增定时备份任务，也没有执行完整恢复演练。

## 验证与证据

- [部署预检](../demo/halo-poc/evidence/public-domain-preflight.json)：公共 DNS、端口空闲、固定 Caddy 镜像。
- [公网功能检查](../demo/halo-poc/evidence/public-domain-verification.json)：有效证书、跳转、四模块、资源、媒体 Range、发现入口和匿名保护。
- [HTTPS登录检查](../demo/halo-poc/evidence/public-domain-login.json)：现有账号正常登录，Session为Secure和HttpOnly，仅执行只读草稿检查。
- [浏览器效果](../demo/halo-poc/evidence/public-domain-browser.json)：用户电脑实际访问域名首页及价格详情，不同计费单位的比较被正确拒绝；[首页截图](../demo/halo-poc/evidence/screenshots/public-domain-home.png)。
- [业务数据对照](../demo/halo-poc/evidence/public-domain-persistence.json)：所有草稿、批准、发布、不可变修订和正文快照保持。
- [备份与部署状态](../demo/halo-poc/evidence/public-domain-artifacts.json)：最新备份、证书持久化、镜像与端口。
- [原有服务检查](../demo/halo-poc/evidence/runtime-after-plugin.json)：原服务 PID、forwarding 与数据库隔离。

配置及脚本均限定在本项目；`deploy-public-domain.py prepare` 是有覆盖保护的首次准备步骤，不要把它当作可任意重跑的更新命令。后续正常重建使用已经固定镜像的 Compose 配置。当前未接 CDN，单文件媒体限制仍为16 MiB；恢复、迁移、负载验收仍见[完整 PoC 进度](./cms-poc-progress.md)。
