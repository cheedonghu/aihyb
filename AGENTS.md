# AGENTS.md

本仓库的工作约定。改动前先读这里。

## 处理 Git 的默认行为

- **提交后默认推送**。`git commit` 完成即执行 `git push` 到 `origin/main`，不需要再问一次。
- 只有这三种情况停下来问：用户明确说"先别推"、存在冲突需要人工决定、推送需要凭据或权限而当前不可用。
- 推送前核对 `git status` 干净、`git log origin/main..HEAD` 只有本次提交、且提交内容没有凭据（见下方"不能提交的东西"）。
- 提交信息用英文，主题行祈使句、一句话说清做了什么；需要解释原因时加正文。不要加 `Co-Authored-By` 之类的尾部标签。

## 不能提交的东西

`.gitignore` 已覆盖，但仍要在 `git add` 后复查：

- `demo/halo-poc/secrets/`（账号密码、会话 Cookie、COS 凭据、业务基线）
- `.env`、`*.pem`、`*.key`
- `demo/halo-poc/backups/`、`data/`
- 根目录的两份商业计划书（`ai测评网内部执行版商业计划书.md`、`【商业计划书】AI好用榜-AIHYB.COM.docx`）

提交前跑一遍形状扫描：`POSTGRES_PASSWORD=`、`BEGIN ... PRIVATE KEY`、`X-CSRF-TOKEN`、`SecretId/SecretKey`、`SESSION=`。

## 仓库结构

- `docs/` —— 中文技术记录。当前入口、部署、验证边界写在这里，是主要的事实来源。
- `demo/halo-poc/` —— Halo PoC 的 Compose 配置、脚本、主题适配、证据。
- `demo/halo-poc/evidence/` —— **要提交的**。JSON 证据、截图是验证结论的凭据，不是临时产物。
- `demo/halo-poc/scripts/` —— 服务器脚本。本地 `scripts/` 与远端 `/opt/aihyb-halo-poc/` 扁平目录对应。
- `video/` —— 视频流水线（edge-tts + Remotion）：`episode.json` 转成带配音、字幕的视频。用法见 `video/README.md`，`build/`、`out/`、`.venv/` 不提交。
- `CONTEXT.md` —— 内容生产的领域术语表。改术语先看这里。
- `plan.md` —— 业务计划，不是技术规格。

## 语言

- 文档、报告、面向用户的文案：中文。
- 代码注释、提交信息、日志字符串：英文。
- 领域术语以 `CONTEXT.md` 为准，别在代码里另造同义词。

## 部署与验证

- 服务器 `5.231.253.4:22333`，目录 `/opt/aihyb-halo-poc`，Compose 项目名 `aihyb-halo-poc`。
- 公网入口是 `http://5.231.253.4/`（纯 HTTP + IP）。域名 `aihyb.com` 因备案暂停解析，不要在任何配置里重新引入。详见 `docs/cms-http-ip-deployment.md`。
- 后台仅走 SSH 隧道 `http://127.0.0.1:18090/console`；公网 `/console`、`/login`、`/uc` 等返回 403。不要把后台开放到公网入口。
- 只操作本项目目录和容器。**不要**对整台服务器跑 `docker prune`、清库、或改动既有服务（SSH、监控、DNS、系统网络）。
- `net.ipv4.ip_forward` 必须保持 `0`。媒体出站只允许通过既有的受限 COS 代理。
- 有覆盖保护的一次性脚本（`prepare` 类）不要当更新命令重跑。
- 改主题模板走 `deploy-theme-adapters.py` 的哈希校验流程，不要直接写 `data/halo/themes`。
- 验证脚本失败要如实报告，不能因为"只改了配置"就跳过。

## 排查时注意

- **本机网络走代理**，会把外部地址解析成 `198.18.x` 假 IP。在本机用 ping / 端口探测判断服务器状态会得出错误结论。查端口用服务器侧 `ss` / `curl`，或用第三方节点。
- Halo 2.26 的站点地址优先读数据库 `system.basic.externalUrl`，改 `HALO_EXTERNAL_URL` 不够，两处必须一致。
