# Halo 媒体与日常运营 PoC

日期：2026-10-17。范围：私有对象存储、图片/附件/短视频与外部视频嵌入、撤稿、归档、完整历史恢复。沿用 Halo 2.26.1、PostgreSQL 17.11、原生文章入口及完整稿件版本保护；未增加业务数据库或 Elasticsearch。

## 存储与访问方式

已连接腾讯云 COS 上海区域的私有 Bucket，使用官方 S3 Object Storage 插件 1.17.0 和 S3 兼容协议。网站适配器只依赖 Halo 附件服务与稳定附件标识；更换 S3 服务商时需要配置新存储策略并迁移文件/映射，不需要把稿件改成厂商 SDK 格式。旧历史附件必须保留可用映射，不能只替换 endpoint。

Bucket 保持私有读写，静态网站托管关闭。凭据仅保存在受限配置中，不进入稿件包、公开 JSON、截图或验证报告。上传后检查原始对象拒绝匿名读取；发布前重新读取对象并校验 SHA-256。对象缺失、权限异常或内容变化会阻止发布。

文章、结构化 JSON、状态与修订继续保存在现有 PostgreSQL；二进制文件保存在 COS。稿件包的 `assets[].object_key` 使用 `attachments/<Halo附件名>`，不是 COS 路径、CDN 域名或临时签名 URL。同一个附件只能被所属内容条目引用。

公开媒体通过版本化网站接口读取：

`GET /apis/api.aihyb.run/v1alpha1/media/{content_id}/file/{revision}/{asset_id}`

服务端检查文章仍公开、修订仍是当前发布版本、附件属于该稿且标记为 public，再从私有存储流式返回。内部签名不重定向给浏览器。支持单段 Range、206 和 416，方便 MP4 播放。响应 `private, no-store`，下载型文件设置附件响应头；预览走需要登录的另一条路径。

新草稿、批准但未发布稿、仅审核附件和已替换旧修订的公开媒体接口均不可读。撤稿/归档停止文章、公开 JSON 与媒体网关访问；历史审核预览继续可用。已经下载到用户设备的内容和第三方视频无法被网站召回。

## 自动化如何交付媒体

四模块仍只有一个稿件内容接收入口。媒体上传是独立的二进制传输步骤，不增加采集器、AI 生成器或另一套内容协议。

1. 自动化先提交无附件的初始草稿，获得稳定 content_id 和 revision。
2. 使用导入身份调用 `POST /apis/console.api.aihyb.run/v1alpha1/entries/{id}/assets?filename=...`。请求体为文件原始字节，Content-Type 为实际类型，不使用 multipart 或 Base64。
3. 回执提供 object_key、content_type、sha256、filename、bytes。**只把协议定义的字段放进 assets**，不要把整个回执直接展开进严格 Schema。
4. 合并完整稿件包，带当前 base_revision 和新的 submission_id，再调用统一接收入口。此时只保存新草稿，附件未公开。
5. 人工通过原生文章中的“稿件数据与审核”预览、审核，再返回原生文章发布。

附件清单示意（尖括号是说明，不能直接提交）：

```json
{
  "asset_id": "screenshot-1",
  "object_key": "attachments/<上传回执的附件名>",
  "content_type": "image/png",
  "sha256": "<上传回执的64位校验和>",
  "alt": "操作界面截图",
  "visibility": "public"
}
```

正文使用 `![说明](asset://screenshot-1)`。文件使用 `[下载资料](asset://guide-1)`；MP4/WebM 链接渲染为带控制条的视频。`private` 附件只列在审核预览中，不允许作为公开正文的内联图片。移除引用不删除底层文件，以支持历史恢复。

当前支持 PNG、JPEG、WebP、GIF、MP4、WebM、PDF、TXT、Markdown、ZIP、DOCX，单文件最多 **16 MiB**，每包最多 50 个附件；这是 PoC 的功能与资源边界，不是恶意文件检测承诺。DOCX 在这里作为下载/归档附件；Word 转统一 Markdown 仍由 Codex skills 完成。

外部视频在正文单独一行写 `[video:bilibili:BV号]` 或 `[video:youtube:视频ID]`，渲染为限定域名播放器，不自动播放。原始 HTML 会转义。外部播放器依赖对方可用性、嵌入许可和访问线路；测试不代表所有来源都可播放。直接写外部图片、COS 地址、签名地址或手工媒体网关地址会被拒绝，统一使用 assets；来源链接同样拒绝绕过媒体保护的地址。

## 运营入口与语义

入口：[原生文章列表](http://127.0.0.1:18090/console/posts) → 受管理文章 → “稿件数据与审核” → “发布状态与历史 / 媒体与附件”。文章信息与 Markdown 仍在原生文章入口维护。附件有未保存修改或正在上传时，结构化表单及审核暂停操作，先保存附件草稿。

| 操作 | 公开状态 | 草稿、批准与历史 |
|---|---|---|
| 撤稿 | 立即停止直接访问；发现入口随后同步 | 保留完整稿件和附件，清除批准 |
| 归档 | 停止公开，暂停编辑、审核和发布 | 保留完整修订和文件 |
| 取消归档 | 保持未发布 | 需重新审核发布；删除/回收态不可操作 |
| 预览历史 | 不改变公开内容 | 只读已被本稿接纳的修订，含对应标题、正文、JSON与附件 |
| 恢复为新草稿 | 现有公开版本不变 | 选定完整历史内容成为新修订，清除批准，需重新审核 |

撤稿/归档会推进运营 epoch，所有保存、审核、发布、恢复及运营写入都使用明确的版本约束。旧窗口不能在撤稿后借用新批准自动发布；并发核心计数变化不应被误判为业务修改，最终写入仍受 Halo 原生 CAS 保护。业务 generation 防止状态改变后又变回原样时，旧操作令牌重新生效。

原生只恢复正文的历史操作和直接删除受管理附件会被拦截，避免标题、JSON 与正文分离。恢复统一使用完整历史入口。导入身份只能提交草稿/上传；编辑可查看和恢复；审核角色可批准；发布角色才能撤稿、归档、取消归档和发布。未引用对象/修订暂不自动清理。

## 本次验证

自动化单元/组件测试 32 项通过，Java 与 Vue 构建通过。真实服务器验证仅写入两个专用虚构条目：`poc-operations-20261017` 和 `poc-media-20261017`；原四模块包、草稿与公开版本保持不变。

| 验证 | 证据 |
|---|---|
| 原生明确修订发布、撤稿、归档、取消归档、完整恢复及过期意图拒绝 | [运营 HTTP 验证](../demo/halo-poc/evidence/operations-verification.json) |
| 真实 COS 图片、文本附件、MP4、私有材料；哈希、原始对象匿名拒绝、分段读取、草稿/旧版本/撤稿隔离 | [COS 验证](../demo/halo-poc/evidence/cos-media-verification.json) |
| 搜索及参数绕过、8条 Feed 的摘要/全文模式、站点地图、公开页面与 JSON 无草稿/内部附件信息 | [发现入口复验](../demo/halo-poc/evidence/media-discovery-verification.json) |
| 四类隔离测试身份的媒体/运营权限 | [角色验证](../demo/halo-poc/evidence/operations-role-verification.json) |
| 实际生成SDK构造PNG/MP4/TXT上传请求，保留File、实际MIME和原始字节/哈希 | [上传客户端验证](../demo/halo-poc/evidence/media-client-verification.json) |
| 原生入口→取消归档→上传私有图片→保存→预览历史→恢复旧稿→重新归档 | [浏览器验收](../demo/halo-poc/evidence/media-browser-verification.json)、[实际上传字节校验](../demo/halo-poc/evidence/media-browser-upload.json) |
| 自有 Halo 重启后完整业务状态、修订、正文快照、COS 字节保持 | [重启对照](../demo/halo-poc/evidence/media-post-restart.json) |
| 插件状态与哈希、受限出站服务、备份校验 | [部署制品](../demo/halo-poc/evidence/media-final-artifacts.json) |
| 原有服务 PID、forwarding 与端口隔离 | [运行检查](../demo/halo-poc/evidence/runtime-after-plugin.json) |

浏览器实际加载图片并播放 2 秒的自建 MP4；外部播放器嵌入可渲染。后台真实上传的PNG与本机315字节原文件及SHA-256一致；附件保存前后表单锁定/恢复正常，私有历史图片可加载。截图：[上传界面](../demo/halo-poc/evidence/screenshots/media-console-upload.png)、[完整历史私有预览](../demo/halo-poc/evidence/screenshots/media-private-history.png)、[恢复后归档](../demo/halo-poc/evidence/screenshots/media-operations-archived.png)。独立代码复核已闭合发现的问题，指定范围无剩余 Critical/Important。

## 对现有服务器的影响与当前边界

保持 `net.ipv4.ip_forward=0`，未开启全局容器转发或修改既有服务。为了让此 PoC 容器访问 COS，增加仅绑定 Docker 桥接地址的专用 CONNECT 服务，仅允许两个明确 COS 主机的 443；拒绝其他域名、IP、端口和普通 HTTP 请求。服务有 8 并发、64 MiB、15% CPU 和超时限制。仅本项目 Halo 的 JVM 指定该代理，数据库无主机端口，Halo 仍只绑定 127.0.0.1:18090。

插件热更新期间曾出现自定义修订索引未注册的 500；精确根因未确认。对本项目 Halo 做冷重启后恢复，并重新验证资源及工作流。升级脚本的“插件 STARTED”不能替代 typed resource 的可读检查。没有因此修改数据库或重启其他服务。

备份脚本已包含 PostgreSQL、Halo 工作目录、受限配置、媒体 Compose overlay 及专用代理脚本/服务定义；COS 对象保留在云端，**不包含在本机 tar 备份里**。本次做备份校验与正常重启持久化核对；完整空实例恢复和失败回退仍按用户要求延后。

CDN 尚未接入。当前媒体回源经网站网关，不能宣称已达到 CDN 直出性能；后续需独立设计公开派生文件、私有来源、撤稿失效和缓存键。站点地图最多约30秒的缓存窗口，Feed/搜索依赖核心协调及事件同步；Feed 失效通知没有持久重试队列。本轮是少量样例验证，未做生产数据量或负载验收。长视频优先使用外部嵌入或后续独立视频分发方案。
