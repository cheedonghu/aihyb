# AI好用榜网站开发仓库

本仓库维护 AI好用榜网站的 Halo 插件、控制台界面、主题适配、稿件协议及可复用部署工具。

| 目录 | 用途 |
| --- | --- |
| [plugin-aihyb](plugin-aihyb/README.md) | Java 21 业务插件、Vue 3 控制台界面和单元测试 |
| [themes](themes/README.md) | 共享模板、哈希校验适配器和固定版本的上游主题 |
| [contracts](contracts/README.md) | 稿件 JSON Schema、OpenAPI 快照和虚构测试样例 |
| [deploy](deploy/README.md) | 按环境区分的 Compose/Caddy 配置及维护脚本 |
| [assets/brand](assets/brand) | 网站品牌源图 |
| [docs](docs/architecture.md) | 当前架构、开发、部署及设计约定 |

首次开发先读 [AGENTS.md](AGENTS.md)、[术语表](CONTEXT.md)及[开发说明](docs/development.md)。

```powershell
python scripts/check-repository.py
cd plugin-aihyb
$env:CI = 'true'
.\gradlew.bat build --no-daemon --console=plain
```

构建产物位于 `plugin-aihyb/build/libs/`。构建只编译源码和运行本地测试，不连接网站或执行部署；首次构建会下载公开依赖。

视频、真实稿件、验证截图、运行报告、商业资料和历史操作脚本已迁出仓库并完整保留。位置及迁移映射见[仓库边界与归档说明](docs/repository-layout.md)。线上服务器的目录、配置、数据及发布状态未因本次整理改变。
