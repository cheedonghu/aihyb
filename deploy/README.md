# 网站部署工具

环境事实、操作步骤和限制见[部署与维护](../docs/deployment.md)。本目录保留可复用配置及维护代码，历史首次迁移和修补脚本已完整移到仓库外。

- `compose.yaml`、`compose.media.yaml`、`compose.public.yaml`：应用、数据库、受限媒体出站和公网入口。
- `caddy/Caddyfile.test`、`caddy/Caddyfile.production`：隔离的测试 HTTP 入口和生产 HTTPS 入口。
- `environments/*.env.example`：非私密环境选择；与 `.env.example` 的私密占位项在目标目录组合，使用既有固定镜像与凭据。
- `scripts/install-plugin.py`：明确指定 JAR、运行目录及访问地址的插件安装/升级。
- `scripts/deploy-theme-adapters.py`：校验、备份和更新主题；部署前先用 `--check`。
- `scripts/backup.sh`：明确选择测试或生产项目的一致性备份。
- `scripts/site_client.py`、`scripts/s3-egress-proxy.py`：私密认证辅助及既有限定 COS 出站代理源码。

运行目录必须与仓库源码分开，真实 `.env`、`secrets/`、`data/`、`backups/`、`runtime/` 全部禁止提交。仓库构建不会运行本目录脚本，也不修改既有服务器。
