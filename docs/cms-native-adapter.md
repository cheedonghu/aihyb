# 原生文章适配与完整版本保护

日期：2026-10-14。确认方案：原生文章入口＋少量稿件扩展＋完整版本保护。首版继续使用现有 PostgreSQL，不增加数据库或 Elasticsearch。

2026-10-15 后续更新：搜索组件恢复，新增两主题搜索模板；搜索、Feed、站点地图六阶段隔离和重启检查通过，当前15项测试通过。修复真实分类标识和Feed别名缓存失效；本页下方的11项测试、7稿件/134修订/58快照及搜索停用描述属于上一轮历史状态，最新证据以[发现入口专项报告](./cms-discovery-verification.md)为准。

## 使用方式

从 Halo 的“文章”进入四模块稿件。标题、摘要及 Markdown 使用原生编辑入口和保存按钮；正文编辑器是宿主注册的“稿件 Markdown”。文章操作或编辑器的“稿件数据与审核”打开来源、任务、价格/评分等结构化字段及审核面板。核对整包、批准明确修订后，返回原生编辑器发布。独立稿件列表菜单已移除。

“草稿隔离”覆盖完整稿件：后台将标题 A 改为 B、价格 49 改为 59，只保存时，审核预览显示 B／59，公开页面、列表和 JSON 仍显示 A／49。审核后发布才同时切换；批准后再次修改需要重新审核。并发旧稿提交返回409，不自动覆盖新稿。

稿件审核仍有一小段扩展，因为 Halo 文章的普通保存/发布状态无法自行校验我们的四种 JSON 数据、来源及完整修订批准。操作入口复用 Halo，业务批准与版本保护由适配器服务执行。

## 存储和提交边界

| 对象 | 职责 |
|---|---|
| ContentRevision | 不可变完整包、Markdown 渲染结果、请求/渲染哈希 |
| 受管理原生 Post | 草稿、批准、公开修订及交付日志的唯一状态权威；公开标题、摘要、releaseSnapshot 与 JSON 发布修订一次 CAS 切换 |
| 原生 Snapshot | 按修订生成 Markdown 原文及正文 HTML；相对固定 baseSnapshot 保存差异 |
| ContentEntry | 稳定内容 ID 与模块注册；旧草稿/发布字段为历史缓存，不承担双主写入 |
| 公开页面/DTO | 从 Post 选中的同一完整发布修订派生，可继续接缓存/CDN |

自动化仍只有 `POST /apis/console.api.aihyb.run/v1alpha1/content-packages` 一个接收入口。外部生成、Word 转换与链接获取留在 Codex skills。接收只创建草稿，不取得发布权限。

受管理文章的原生保存请求重定向到受 RBAC 保护的适配端点，Halo 对重定向后的独立请求重新鉴权。私有编辑响应显示新标题/摘要，公开 Post 的 spec 保持已发布值。正文保存携带修订令牌；同一次原生保存的元数据响应只承认它实际提交的修订，不能把其他编辑者随后保存的令牌承接给旧正文。

原生发布必须提交当前窗口成功保存响应中的 headSnapshot。服务端同时检查当前草稿、批准修订及 Post 并发版本；未批准、过期或缺少明确版本均409。保存完全相同内容不创建新修订，不撤销已有批准。

原始稿件资源与受管理快照不可直接写入；受管理 Post 的 UC 写入、PATCH、删除/撤稿等当前不支持的路径拒绝403。普通原生文章保持原有流程。修改 URL、作者、封面、原生分类/标签暂拒绝422；模块分类固定映射，包内标签依然包含在完整修订中。此限制需在正式运营前按需求补齐，不能宣称原生所有功能已适配。

## 本轮验证

| 项目 | 实际结果及证据 |
|---|---|
| 迁移 | 原6个稿件映射到原生 Post，四模块草稿、批准及公开内容保持一致；[记录](../demo/halo-poc/evidence/native-migration.json) |
| 原生保存/审核/发布 | 四模块逐一测试标题、摘要、正文、data 的 B 草稿；公开 A 不变，批准后整包切换，旧版本与 UC 写入拒绝，最后恢复样例；[记录](../demo/halo-poc/evidence/native-verification.json) |
| 真实浏览器 | 原生保存按钮同时保存标题和正文，数据面板49→59并审核，原生发布按钮切换完整修订；[记录](../demo/halo-poc/evidence/native-browser-workflow.json) |
| 最终发布按钮复验 | 最新插件的未审核B发布显示明确提示；已审核渲染诊断稿的原生发布成功，no-op保存保留批准，并绑定窗口中的明确快照；[记录](../demo/halo-poc/evidence/native-browser-final.json)、[保护截图](../demo/halo-poc/evidence/screenshots/native-unapproved-publish.png) |
| 原生权限 | 编辑可保存不能审核/发布；审核者可批准不能发布；发布者可发布明确已批准快照；导入者不能读原生草稿或发布；[矩阵](../demo/halo-poc/evidence/native-role-verification.json) |
| 自动检查与审查 | 11项 Java 测试、完整构建和 UI 类型检查通过；独立复核已关闭保存响应令牌承接、发布版本缺省及 Prism 全局冲突，最终无 Critical/Important |
| 服务隔离 | SSH、监控、Docker及系统网络/DNS/时间进程的原基线PID保持；forwarding=0，Halo本机绑定，数据库无主机端口；[检查](../demo/halo-poc/evidence/runtime-after-plugin.json) |

最终私密备份为 `/opt/aihyb-halo-poc/backups/20261014T141926Z`，包含数据库、Halo工作目录、插件/主题、运行配置及账号文件；只短暂停止本项目Halo，13秒恢复readiness。重启前后全部业务状态、原始整包、修订与稿件快照哈希一致，四基准模块仍为公开A/待审B；[持久化结果](../demo/halo-poc/evidence/native-final-persistence.json)。此备份未做完整空实例恢复，不作为完整P7通过证据。

## 两套主题专项

采用 Earth 1.15.3 与 Anatole 2.1.0。Anatole 使用[官方2.1.0标签源码](https://github.com/halo-dev/halo-theme-anatole/tree/v2.1.0)及自带构建资源；源制品摘要记录在[来源记录](../demo/halo-poc/evidence/anatole-source.json)。两者采用 GPL-3.0。Anatole 在当前实例标记为开发模式，此结果不能直接作为生产缓存或负载能力证明。

适配提供 `layout :: html(head, content)`、四模块导航、表格滚动和内容样式；原生文章继续使用主题自己的 post 模板。代码高亮实例为私有作用域，价格脚本限定稿件正文，不覆盖其他页面的 Prism。两主题配置和原文件均保留；部署检查原文件/已知适配哈希，备份变更文件并通过原生 API 重载与清除模板缓存。[适配清单：当前10文件，含后续两份搜索模板](../demo/halo-poc/evidence/theme-adapters.json)

切换顺序 Earth → Anatole → Earth。为四基准模块分别保留“公开 A＋未审核 B”，并新增一个代码、提示词、图片和宽表格的虚构教程。冻结后共有7个稿件条目、6个公开条目、134份不可变修订、58份稿件原生快照；各次切换的 ID、完整包、Post业务状态、正文快照和哈希完全相同。Halo阅读计数本来会变化，未把它误判为内容版本变化。权限验证阶段之后对渲染诊断稿的审核/发布是明确测试写入，不属于主题切换阶段。

- [Earth 初始结果](../demo/halo-poc/evidence/theme-earth-initial.json)、[Anatole 结果](../demo/halo-poc/evidence/theme-anatole.json)、[切回 Earth](../demo/halo-poc/evidence/theme-earth-restored.json)：四模块列表/原生详情、首页导航、来源、唯一 canonical、样式脚本、任务筛选及分页、鉴权预览及匿名拒绝通过。`/aihyb/fallback` 保底页面可访问。
- [浏览器尺寸记录](../demo/halo-poc/evidence/theme-responsive.json)：两主题分别检查四模块列表、四详情及渲染样例，390px/1440px共36种页面尺寸组合；页面宽度不超过视口。手机榜单和价格表660px，容器约315–326px，单独横向滚动。
- [价格混合单位检查](../demo/halo-poc/evidence/theme-comparison.json)：月付筛选及选项比较通过；混选月付/年付被自动拒绝。代码有高亮，提示词保留，本站测试图片可见。图片测试不等于对象存储/私有附件验收。

截图：[Earth 手机 Markdown](../demo/halo-poc/evidence/screenshots/earth-mobile-render.png)、[Anatole 手机 Markdown](../demo/halo-poc/evidence/screenshots/anatole-mobile-render.png)、[Earth 价格](../demo/halo-poc/evidence/screenshots/earth-desktop-pricing.png)、[Anatole 价格](../demo/halo-poc/evidence/screenshots/anatole-desktop-pricing.png)。未单独计量主题适配工时，不宣称换任意主题无需开发。

测试站的可选 PluginSearchWidget 1.7.1 样式接口重复出现偶发 ZipException；JAR完整性检查通过，原生重载及测试站重启未消除。已仅停用此 PoC 插件，保留制品、配置与启用参数。[记录](../demo/halo-poc/evidence/search-plugin-disabled.json)。全文搜索未作为本轮验收通过项，未改动服务器其他服务。

## 尚需完成

主题切换专项通过，完整 P1–P8 仍有门槛：正式 Feed/站点地图和缓存失效、两位运营试用、完整字段编辑与撤稿/归档、实际 skill 接入、对象存储与私有附件、视频组件/CDN、空实例恢复迁移与升级回滚、规定规模和持续负载测试。公开列表目前按原生发布状态汇总后筛选分页；大量条目性能需 P8 测量和按需建立发布读模型，不能沿用旧报告的“发布索引已验收”结论。

当前四基准模块故意保留 THEME_DRAFT_* 待审标题；公开内容仍是原样例。可从[原生文章](http://127.0.0.1:18090/console/posts)进入核对，从[四模块](http://127.0.0.1:18090/aihyb)查看公开版。
