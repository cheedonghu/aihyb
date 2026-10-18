# Halo 四模块测试环境

当前服务器：`5.231.253.4`，SSH 用户 `root`，端口 `22333`。

2026-10-17 已增加域名预览入口：[网站](https://aihyb.com/)、[四模块](https://aihyb.com/aihyb)、[后台](https://aihyb.com/console/)。沿用原Docker数据映射；新增独立Caddy容器负责HTTPS，完整持久化/备份说明见[域名部署记录](../../docs/cms-domain-deployment.md)。日常使用域名，下面的本机地址保留作隧道调试。

Halo自身仍只绑定服务器本机地址，公网通过Caddy代理；当前电脑也可使用已有 SSH 隧道：

- 网站：http://127.0.0.1:18090/
- 后台：http://127.0.0.1:18090/console
- 原生文章与稿件审核入口：http://127.0.0.1:18090/console/posts
- 新四模块原型：http://127.0.0.1:18090/aihyb
- 本地账号文件：`secrets/access.txt`（私密，不提交代码库）

重新连接时使用用户提供的私钥，运行：

```powershell
ssh -i "C:\Users\east\OneDrive\files\SyncFile\mima\key\east-nopw" -p 22333 -N -L 127.0.0.1:18090:127.0.0.1:18090 root@5.231.253.4
```

已有隧道占用18090时不要重复启动。远端目录 `/opt/aihyb-halo-poc`，Compose项目名 `aihyb-halo-poc`。`compose.yaml`有资源上限、日志轮转、专用网络；数据库不映射主机端口。实际镜像在远端 `.env` 固定digest，该文件含数据库密码，仅远端保存；`evidence/images.txt`可公开核验镜像，不含密码。

远端曾多次关闭 SSH 隧道，已补充 `scripts/open-tunnel.ps1`：只绑定本机18090，连接断开后5秒重试，发现端口已占用则退出，不修改远端配置。可在终端运行并以 Ctrl+C 停止；本轮启动的隐藏副本 PID 记录于 `secrets/tunnel-supervisor.pid`，日志限制在约16KB。停止隐藏副本时应核对该 PID 对应此脚本，然后终止该进程树。

当前运行 Halo 2.26.1、PostgreSQL17.11 和 plugin-aihyb。四种统一稿件共用单接收入口，人工操作改为原生文章入口及少量结构化数据/审核扩展，公开标题和正文快照与 JSON 版本一起发布。Earth 1.15.3 与 Anatole 2.1.0 已完成切换及手机/桌面验证。四模块保留已发布 A 与未审核 B，方便继续核验隔离；所有数据均为虚构。

包格式、样例、接口及当前限制见 [协议说明](./contracts/README.md)。完整 PoC 尚未通过：跨产品对比、两位运营试用、完整空环境恢复迁移升级、规模与负载验收仍待完成。2026-10-17 已接入私有腾讯 COS（S3兼容），支持图片、附件、MP4/WebM与受限外部嵌入，以及撤稿、归档和完整历史恢复，详见[媒体与运营报告](../../docs/cms-media-operations.md)；CDN 尚未接入。搜索插件已恢复并作字节不变的资源封装加固；搜索、8条Feed路由、站点地图的六阶段草稿隔离及重启通过，精确间歇触发仍未定位。新增两主题搜索模板，Feed 1.5.0 现在是业务插件运行依赖；[专项报告](../../docs/cms-discovery-verification.md)记录了修复、证据和边界。

脚本均只面向这个隔离的测试目录：

- `scripts/bootstrap-host.sh`：官方Docker仓库安装，不整体升级系统；保留主机初始forwarding=0。
- `scripts/deploy.sh`：生成受限随机数据库密码、拉取镜像并固定digest、启动独立Compose项目。
- `scripts/initialize.py`：初始化站点、正常RSA登录、创建四模块样例；不输出凭据。
- `scripts/verify-initial.py`：正文草稿/发布/恢复、原生标题保存行为、匿名权限及样例页检查。发现标题直接保存即公开，不代表P1完整通过。
- `scripts/build-package-fixtures.py`：同步四种样例及插件内外 JSON Schema。
- `scripts/install-plugin.py`：通过 Halo 官方安装/升级接口部署本插件。
- `scripts/verify-native.py`：四模块原生保存、审核、发布、旧版本和 UC 写入旁路检查，会恢复样例为新修订。
- `scripts/verify-discovery.py`：四模块搜索/Feed/站点地图六阶段检查，会写入虚构测试版本，再按修订和配置所有权恢复；要求原基准草稿未批准。
- `scripts/verify-discovery-state.py`：冻结后检查主题切换及重启不改变业务状态，并只读检查发现入口。
- `scripts/prepare-search-resourcefix.py`、`install-search-resourcefix.py`：仅加固搜索插件两个静态资源的ZIP保存方式，保持所有条目内容字节相同，有原版哈希保护和备份。
- `scripts/probe-search.py --after`、`probe-search-mixed.py --after`：小规模资源/页面/Range检查，不作为压力测试。
- `scripts/repair-native-categories.py`：修复已知旧分类引用并核对其余业务状态不变。
- `scripts/record-discovery-artifacts.py`：本轮已知备份、运行插件状态、制品哈希及有界日志摘要核验，不输出私密内容。
- `scripts/prepare-theme-adapters.py`、`deploy-theme-adapters.py`：生成及部署有原文件哈希保护和备份的两主题适配。
- `scripts/verify-themes.py seed`：生成渲染样例和四模块 B 草稿，然后冻结基线；后续 earth-initial/anatole/earth-restored 检查只切换主题并对照内容状态。
- `scripts/verify-native-roles.py`：已有测试身份的原生入口权限检查，常规保存仅执行 no-op。
- `scripts/verify-packages.py`：四模块完整草稿/批准/发布/恢复、幂等、并发、严格格式和四类测试身份的权限验证，会写入标明虚构的 PoC 数据。
- `scripts/verify-package-additions.py`：只读验证模块筛选后分页、绑定修订预览，并记录持久化摘要。
- `scripts/verify-backup-restore.py`：将最新备份恢复到固定命名的新独立测试库；已有同名库则拒绝覆盖，不覆盖在线数据库，不代表完整实例恢复。
- `scripts/record-runtime.py`：只读核对既有服务PID、端口绑定、forwarding与空闲资源，不读取凭据或进程参数。
- `scripts/backup.sh`：短暂停止本项目Halo，备份数据库、工作目录、配置及私密账号文件，恢复本项目运行；尚未完成空环境恢复演练。

当前网络保留IP forwarding=0；`compose.media.yaml` 为本项目 Halo 设置专用桥接 CONNECT 出站，只允许两个已知 COS 主机443端口。首次部署使用 `install-s3-egress.py` 的受限服务，后续重建 Halo 需带 `-f compose.yaml -f compose.media.yaml`。不得把该出站当成通用采集网络。媒体脚本 `configure-s3-media.py` 仅用于首次配置，会拒绝覆盖未知现有策略；不要重复执行来更换凭据。

媒体运营验证脚本：`verify-operations.py`、`verify-cos-media.py`、`verify-media-discovery.py`、`verify-operations-roles.py`；只操作各自指定的虚构条目。`verify-media-persistence.py freeze/check` 冻结私密基线并检查本项目重启后的完整版本/真实媒体，`record-media-artifacts.py` 记录非私密制品和备份证据。图片、附件、视频每次实际上传都会保留私有测试对象，当前没有自动清理历史或孤立对象。

完整计划与进度见 `docs/cms-poc-plan.md`、`docs/cms-poc-progress.md`。不要对整台服务器运行清库、Docker prune 或改变既有服务。
