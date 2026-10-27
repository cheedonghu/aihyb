# 部署与维护

本页保留当前环境事实及可复用操作边界。2026-10-27 的仓库整理只改本地目录，没有执行 SSH、服务器命令或部署。原服务器仍使用既有扁平目录和配置，不会自动采用仓库的新布局。

| 项目 | 测试 | 生产 |
| --- | --- | --- |
| SSH | `root@5.231.253.4:22333` | 别名 `aihyb-prd`，`121.199.66.66:22333` |
| 服务器目录 | `/opt/aihyb-halo-poc` | `/opt/aihyb-halo-prd` |
| Compose 项目 | `aihyb-halo-poc` | `aihyb-halo-prd` |
| 前台 | `http://5.231.253.4/` | `https://aihyb.com/` |
| 后台 | SSH 隧道 `http://127.0.0.1:18090/console` | `https://aihyb.com/console/` |
| 备用后台隧道端口 | `18090` | `18092` |

## 配置边界

`deploy/compose*.yaml`、`deploy/caddy` 和 `deploy/environments` 保存供后续审查使用的环境模板。示例中的镜像和密码占位值必须从目标环境的私密配置中确认；它们不能直接覆盖现有服务器 `.env`。本地可用 `docker compose --env-file <目标配置> config --quiet` 校验，不能将此操作等同部署。

生产 Caddy 配置保留 HTTPS 主站、`www`/HTTP 跳转及正常后台认证。只对 `/actuator/globalinfo` 与 `/actuator/info` 保留 Halo 原生处理：前者为公开启动配置，后者匿名应为 401；其他 actuator、OpenAPI、Swagger 继续 404。测试公网账号路由继续 403，Halo 本身只绑定回环，数据库不发布端口。

生产域名验证文件放在服务器项目目录的 `public/site-verification/`，由 Caddy 只读挂载并在根路径提供 `baidu_verify_*.html`；文件名和内容须保持原样，验证成功后仍须保留。该目录属于站点运行文件，不进入 Git，备份脚本会一并保存。2026-10-27 已为百度验证增加此挂载和路由；服务器继续使用原有扁平目录。

生产页脚备案为 `浙ICP备2026090602号`，链接 `https://beian.miit.gov.cn/`，通过 Earth 主题现有“备案设置”维护。生产管理员名从目标服务器 `secrets/admin-username.txt` 读取，密码从同目录 `admin-password.txt` 读取；旧账号保留历史作者引用。不要复用测试环境默认账号。

## 日常更新

先在本地完成构建和测试，再明确目标、核对其运行版本及备份。插件更新脚本是 `deploy/scripts/install-plugin.py --artifact <JAR>`，主题更新脚本是 `deploy/scripts/deploy-theme-adapters.py --theme theme-earth --check`。两者需要显式设置 `AIHYB_SITE_ROOT`、`AIHYB_SITE_URL` 和对应私密身份，默认不会选择某台服务器。仓库脚本路径与服务器运行目录是两个概念；部署时只传递经过审查的制品和配置，不同步整个资料归档。

主题必须先 `--check`，再去掉 `--check` 发布；全量哈希不匹配时停止。备份位于目标项目 `backups/`，部署状态写到目标 `runtime/`，都不进入 Git。哈希清单更新规则见[主题说明](../themes/README.md)。

`deploy/scripts/backup.sh` 要求显式的 `AIHYB_SITE_ROOT` 和 `AIHYB_COMPOSE_PROJECT`，且只接受上表的目录/项目组合。它短暂停止本项目 Halo，备份数据库、文件、配置及密钥后恢复，并检查应用就绪。该脚本不创建定时任务，备份会短暂中断访问，不能在仓库整理或本地构建时执行。

完整新实例恢复涉及数据库、Halo 工作目录、私密配置、固定镜像和 COS 引用，须在独立目录演练并验证业务版本。旧的首次迁移脚本已归档，不应作为日常更新命令；本轮没有新建或验证通用恢复工具。

## 网络与检查

只操作本项目目录和容器；不执行全机清理，不改既有 SSH、监控、DNS 或系统网络。`net.ipv4.ip_forward` 必须保持 0，媒体出站继续使用既有 COS 限定代理。代理源码保存在 `deploy/scripts/s3-egress-proxy.py`，仓库整理不安装或启动它。

本机代理可能将外部域名解析到 `198.18.x`；不能据本机 ping 或端口探测判断服务器状态。检查用服务器侧 `ss`/`curl` 或第三方节点。Halo 站点地址同时核对 `.env` 对应的 `HALO_EXTERNAL_URL` 与数据库 `system.basic.externalUrl`，二者必须一致。

后续发布至少核对首页与四模块、原生文章、图片、搜索/Feed/站点地图、后台及匿名权限、草稿隔离和本项目就绪状态。截图、检查 JSON 和日志写到外部归档或被忽略的报告目录，源码仅维护可复现检查逻辑及必要样例。
