# AGENTS.md

本仓库仅维护 AI好用榜网站开发。改动前先读本文、CONTEXT.md；视觉和信息结构调整先读 docs/design-references.md。

## Git 默认行为

- 提交后默认推送到 origin/main，不再重复询问。只有用户明确说先别推、冲突需人工决定、或当前缺少推送凭据/权限时暂停。
- 推送前核对 git status 干净，git log origin/main..HEAD 只有本次提交，暂存内容不包含凭据或运行数据。
- 提交信息用英文祈使句，不加 Co-Authored-By 等尾部标签。
- 用户已有的源码和改动必须保留。目录整理先做仓库外归档，再修改引用；不使用清理或重置命令丢弃文件。

## 仓库范围与目录

- plugin-aihyb/：Java 21 Halo 业务插件、Vue 控制台界面、测试和必要的生成 API 客户端。
- themes/：共享主题源文件、适配器、固定上游源码及必要部署哈希；维护时保留上游许可。
- contracts/：标准稿件 JSON Schema、OpenAPI 快照与虚构测试样例；插件内 Schema 必须同步。
- deploy/：按测试/生产区分的 Compose、Caddy 模板及可复用维护脚本。一次性迁移、修补过程不继续积累在源码中。
- assets/brand/：网站品牌源图；运行时品牌资源随插件或主题打包。
- docs/：当前架构、开发、部署、设计偏好及必要技术决策；CONTEXT.md 为领域术语表。
- scripts/：本地仓库检查工具，默认不连接网站、不写运行数据。

视频制作、真实稿件/配图、商业计划、设计预览、运行证据和历史操作资料存放在仓库外。此次归档位置及旧新路径映射见 docs/repository-layout.md；不得删除归档或重新整体加入 Git。旧 demo 目录不再用于开发。

## 禁止提交

- secrets/、backups/、data/、runtime/，以及真实 .env、*.pem、*.key、会话 Cookie、口令、COS 凭据和业务基线。
- 依赖、构建产物、缓存、日志、检查报告和浏览器截图；必要虚构样例、接口快照及经过审查的依赖/部署哈希元数据可以提交。
- 视频目录、真实文章素材、商业资料与仓库外归档。

git add 后复查文件名单，并扫描 POSTGRES_PASSWORD=、BEGIN ... PRIVATE KEY、X-CSRF-TOKEN、SecretId/SecretKey、SESSION=；逐项区分规则说明、变量名和真实凭据。不得输出实际凭据。

## 本地验证

- 从根目录运行 python scripts/check-repository.py，检查引用、协议同步和主题哈希。
- 在 plugin-aihyb/ 设置 CI=true 后运行 Gradle Wrapper 的 build；验证 Java 测试、前端测试、类型检查和打包。构建不要求运行中的 Halo，不调用部署脚本。
- API 客户端及协议快照作为构建输入维护；生成客户端的联网刷新只能明确指向隔离测试实例。私有请求头文件通过 AIHYB_OPENAPI_HEADERS_FILE 指定。
- 报告与截图写到被忽略的 build/reports 或外部归档，不再要求提交所有 evidence。失败如实报告，不能以只改目录或配置为由跳过验证。

## 环境与部署约束

- 目录整理和本地构建不授权执行线上部署。每次服务器操作前明确目标环境；本地源码迁移不改服务器既有目录布局。
- 测试：5.231.253.4:22333，目录 /opt/aihyb-halo-poc，Compose 项目 aihyb-halo-poc；公网 http://5.231.253.4/，后台只走本机 18090 SSH 隧道，公网账号类路径保持 403。
- 生产：121.199.66.66:22333，SSH 别名 aihyb-prd，目录 /opt/aihyb-halo-prd，Compose 项目 aihyb-halo-prd；主站 https://aihyb.com/，后台 https://aihyb.com/console/，www 和 HTTP 跳转主站；备用后台隧道用 18092。
- 两台主机的 Halo 只监听回环，数据库不发布端口。生产保留后台依赖 /actuator/globalinfo（公开启动配置）和 /actuator/info（Halo 原生认证，匿名 401），其他 actuator、OpenAPI、Swagger 保持 404。
- 生产备案号为浙ICP备2026090602号，链接 https://beian.miit.gov.cn/，只通过 Earth 主题现有备案设置维护。
- 生产当前管理员用户名从 secrets/admin-username.txt 读取，口令从 admin-password.txt 读取；旧用户停用但保留历史作者引用。不得使用测试旧默认账号代替生产身份或将口令写入文档。
- 维护客户端要求明确设置 AIHYB_SITE_ROOT、AIHYB_SITE_URL；备份另要求 AIHYB_COMPOSE_PROJECT。测试写入脚本不得直接用于生产。
- 只操作本项目目录和容器。禁止全机 docker prune、清库或改动既有 SSH、监控、DNS、系统网络。net.ipv4.ip_forward 必须保持 0；媒体出站只能通过既有 COS 限定代理。
- 主题更新走 deploy/scripts/deploy-theme-adapters.py 的全量哈希校验和备份流程，先 --check，不直接覆盖 data/halo/themes。未知哈希停止；有覆盖保护的初始化步骤不当更新命令重跑。
- Halo 站点地址同时核对 HALO_EXTERNAL_URL 与数据库 system.basic.externalUrl。本机代理会产生 198.18.x 假 IP，不能凭本机 ping/端口探测判定服务器状态。

## 语言

文档、报告和面向用户文案用中文；代码注释、提交信息和日志字符串用英文。领域术语以 CONTEXT.md 为准。当前连接与维护说明见 docs/deployment.md。
