# 统一稿件包协议 1.0

冻结日期：2026-10-14；2026-10-17 补齐媒体与运营行为。机器校验以 [JSON Schema](./content-package-v1.schema.json) 为准；四份可直接提交的虚构样例位于 [examples](examples/)。修改协议时同步生成插件内 Schema，不能只改本文。

## 四种包

| kind | 发布单元 | 必需业务信息 | 完整样例 |
|---|---|---|---|
| review | 一篇测评或原文导读 | task、tool_id/name/version、content_origin、结论及来源证据 | [review.json](examples/review.json) |
| tutorial | 一篇教程 | task、tool_id/name/version、前置条件、核验状态与时间 | [tutorial.json](examples/tutorial.json) |
| leaderboard | 同一任务与口径的一份榜单 | benchmark、方法版本、统计时间、单位、排序方向、排名方式、条目与来源 | [leaderboard.json](examples/leaderboard.json) |
| pricing | 一个产品的方案集合 | 产品、任务、方案 ID、金额/币种/单位/扣款周期、地区/渠道、适用条件、免费额度、限制、来源与核验时间 | [pricing.json](examples/pricing.json) |

统一外层包含 `schema_version: "1.0"`、`content_id`、`submission_id`、`kind`、`content`、`data`、`sources`、`assets`、`provenance`；更新另带 `base_revision`。正文为 `content.body_markdown`，标题、摘要、标签也是稿件的一部分，整体审核发布。

2026-10-23 增加可选展示栏目：`content.section: "news"` 与 `content.news_date: "YYYY-MM-DD"` 必须同时提供，日期代表本站收录日期。两项省略时按原模块展示；两项提供时进入「最新资讯」，从四模块公开列表中排除。该字段与正文一起审核发布，未发布草稿不影响公开栏目。`kind` 继续标识不可变的稿件数据结构，已有原文导读可保留原始结构化记录与历史修订；公开 API 的 `kind` 则投影为展示栏目，支持 `kind=news`。后台稿件数据表单可切换展示栏目并填写日期。完整行为见 [最新资讯栏目记录](../docs/architecture.md)。

Codex skill 可在本机保留 manifest.json、body.md、data.json 和输入材料，交付脚本合并为一个 UTF-8 JSON 对象。插件只接收对象，不处理 ZIP、DOCX 或采集任务，不执行包中的 JavaScript。来源 `usage_note` 和生成记录只在后台展示，公开页保留原文链接与作者。

## 入口和回执

自动化仅使用一个提交入口：

`POST /apis/console.api.aihyb.run/v1alpha1/content-packages`

需要 Halo 身份认证与 `plugin-aihyb-importer` 权限。使用 Halo 已支持的认证方式；PoC 的专用测试脚本使用正常登录会话及 CSRF，不开放免鉴权入口。正式 skill 接入时另配置受限凭据，不保存管理员密码到稿件包。

成功 HTTP 200 返回 `content_id`、`revision`、`replayed`、`preview_url`、`published_revision`。接收只创建草稿，不自动审核、发布。更新前编辑身份可读取 `GET .../entries/{id}` 获取当前 `draft_revision`；仅导入身份没有查看完整草稿权限，自动化可保存上次回执，冲突交人工核对。

- `content_id` 稳定不变；`submission_id` 每次新交付生成一次，同次网络重试沿用。
- 幂等范围为提交身份与 submission_id；同一身份同 ID 不同内容返回 409。不要在不同账号间依赖同 ID 去重。
- 新条目不得带 base_revision；更新必须引用当前草稿。并发更新只接受一个，其余 409。
- 重放以前已接受的交付会返回原修订，不把当前草稿或公开版本退回。回执中的原预览若已过期会返回 409。
- 422 表示格式、引用或业务规则错误；401/403 表示身份/权限不满足；409 表示版本/幂等冲突；过大请求被 413 或框架大小限制拒绝。失败时不得自动换新 ID 无限重试。

## 后台管理接口

下列路径均相对 `/apis/console.api.aihyb.run/v1alpha1`：

| 操作 | 路径 | 约束 |
|---|---|---|
| 列表 | GET /entries?kind=pricing&page=1&size=20 | 模块筛选后分页，size 1–100 |
| 查看完整草稿 | GET /entries/{id} | 编辑及以上权限 |
| 网页预览 | GET /entries/{id}/preview?revision=回执修订 | 私有、禁止缓存；修订过期 409 |
| 审核 | POST /entries/{id}/review | `{ "revision": "r-…", "decision": "approve", "note": "…", "operation_epoch": "状态回执值" }`；退回用 request_changes |
| 发布 | POST /entries/{id}/publish | `{ "revision": "r-…", "operation_epoch": "状态回执值" }`；必须是当前且已批准的修订 |
| 运营状态 | GET /entries/{id}/operations | 返回业务 version、operation_epoch、归档/公开标记和修订 |
| 撤稿/归档/取消归档 | POST /entries/{id}/operations | `{ "version": "状态回执值", "action": "withdraw或archive或unarchive" }`；发布权限 |
| 完整历史 | GET /entries/{id}/history?page=1&size=20 | 只列本稿接纳修订；size 1–100 |
| 历史预览 | GET /entries/{id}/history/{revision}/preview | 私有、只读，含对应标题、JSON及附件 |
| 恢复为新草稿 | POST /entries/{id}/restore | `{ "version": "状态回执值", "revision": "历史修订" }`；不自动发布 |
| 上传二进制 | POST /entries/{id}/assets?filename=文件名 | 原始字节请求体、实际 Content-Type；16 MiB上限 |

运营状态回执的 version 是不透明业务令牌，不能用原生 metadata.version 替代。review/publish 必须携带本窗口读取的 operation_epoch；旧条目未发生运营操作时兼容空值作为 `0`，撤稿/归档后不可省略。原生发布的查询参数为明确的 headSnapshot 和 operationEpoch。

上传接口的 OpenAPI 请求体为各支持 MIME 下的 `string/binary`，生成 SDK 接受 File，并返回 Upload。多 MIME 生成器会选择一种默认 Content-Type，调用时**必须在 options.headers 中覆盖为实际文件类型**；后台上传已按此调用生成客户端。不可把图片/视频按 application/json 发送。历史列表和存储配置查询具有明确响应类型。

保存新草稿即清除当前批准状态。受管理原生 Post 原子维护草稿、批准及发布修订；发布同时切换公开标题、摘要、releaseSnapshot 和 JSON 修订。包内标签、结构化数据及来源来自同一完整修订；原生分类固定按模块映射，原生标签编辑暂不开放。ContentEntry/ContentRevision 外部 HTTP 写接口禁用；受管理 Post、Snapshot 写入经过版本保护。直接改数据库不属于支持的操作。

公开 JSON：`GET /apis/api.aihyb.run/v1alpha1/contents`（kind/task/page/size）及 `/contents/{id}`。只返回发布修订；未发布条目为 404。HTML 列表入口 `/aihyb`，详情跳转原生 `/archives/aihyb-{content_id}`；主题首页增加四模块导航。人工操作入口为 `/console/posts`，稿件数据面板需要带明确 content/post 参数，不再有独立稿件列表菜单。

## 类型规则与当前边界

- ID 为小写字母、数字、连字符，最长80；标题200字符，摘要2000字符，Markdown最多65536字符，总请求最多128 KiB，JSON 深度40；重复键、尾随 JSON、未知字段拒绝。
- 金额与分数使用十进制字符串，未知用 `null`；`"0"` 表示实际零，不能用零替代未知。HTTP 序列化保留明确的 null。
- 榜单最多200条，名次为1–2147483647；检查排序方向、重复条目、并列规则、percent 不超过100。不同基准不合并排行。
- 单产品价格最多100方案；保留会员与 API、月价与年扣款的区别。价格页读取全部已发布方案，按币种及会员/API 分组默认展开；不要求手选比较对象。
- 价格方案可补充 `usage_quota`、`reset_rule`、`benefits`、`status`、`tax_note`；旧包兼容，缺失项公开标为待补充。`data.task_usage` 可记录同任务体感额度消耗：任务及共同条件、方案 ID、模型版本、额度池分母及重置、消耗百分比（0–100）、耗时秒数、返工次数、样本数、结果、测试时间及来源 ID。方案和来源必须存在；不同条件分别展示，不根据百分比推算跨产品性价比。完整虚构样例见 [pricing-ui-a.json](examples/pricing-ui-a.json) 与 [pricing-ui-b.json](examples/pricing-ui-b.json)。
- 来源 ID 唯一，业务 source_id 必须存在。链接只允许 HTTP(S)，不含账号密码；未知作者、原始日期不可编造。
- Markdown 支持常用语法与 GFM 表格，原始 HTML 被转义，危险链接清洗。图片/文件用 `asset://附件ID`；MP4/WebM 渲染为播放器；独占行 `[video:bilibili:BV号]` 或 `[video:youtube:视频ID]` 使用受限外部播放器。
- assets 已接入私有 S3：object_key 为 `attachments/<上传回执附件名>`，发布前核对归属、类型、私有性与文件 SHA-256。每包最多50个附件；私有材料只进审核预览。公开网关仅服务当前发布修订且标为public的附件；外部图片/存储/签名链接不能绕过该机制。[媒体交付步骤](../docs/architecture.md)。
- 每条内容最多接受1000次交付；达到后返回409，正式运行前需规划归档。失败的并发写可能留下未引用修订，不公开，后续需定期回收策略。
- PoC 为立即人工发布；已提供撤稿、归档、历史列表与完整历史恢复。取消归档不会自动发布，恢复产生新草稿并清除批准。无定时发布或跨包原子发布；不自动删除历史文件。

## 保存与备份

运行数据由 Halo ReactiveExtensionClient 写入现有 PostgreSQL。包与渲染结果存为修订中的文本，筛选常用字段和发布指针单独建索引；没有另建业务数据库，也没有假设它们是 PostgreSQL 原生 jsonb 列。大附件不进入数据库。

本机原稿包用于自动化重放与审阅，数据库用于修订、状态、并发和权限。二者配合；本机 JSON 不能替代后台数据库备份。备份包括 PostgreSQL、Halo 工作目录、插件、配置及受限凭据。当前公开响应禁止共享缓存；接 CDN 前需单独实现发布版本缓存、失效与私有路径隔离。

## 构建与验证

规范 Schema、虚构样例和 OpenAPI 快照均在本目录维护。运行 `python contracts/scripts/sync-schema.py` 将 Schema 同步到插件资源，再从根目录执行 `python scripts/check-repository.py`。

控制台生成客户端随源码提交，常规构建无需运行中的 Halo。API 变更时从隔离测试实例取得并审查接口快照，再离线生成客户端，步骤见[本地开发](../docs/development.md)。

Console 复用 Halo 的 FormKit；任务选择器采用官方要求的 `{ label, value }[]`，避免值已保存但选项名称空白的问题。[Halo 表单组件说明](https://docs.halo.run/developer-guide/form-schema#select)

本目录的稿件和媒体样例均为虚构测试输入。真实稿件、发布回执及历史 HTTP 验证记录在仓库外归档；数据库与文件备份仍按[部署说明](../docs/deployment.md)执行。
