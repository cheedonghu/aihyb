# AI好用榜 CMS 技术选型报告

调研日期：2026-10-13（北京时间）｜状态：用户已选定 Halo；完成文档与源码调研，具体实现待 PoC 验证。

## 1. 结论先行

**主选：Halo 社区版 + 成熟主题 + 轻量业务插件，按实际需求增长接入独立 Spring Boot 服务。备选：WordPress 社区核心 + 成熟区块主题 + 小型自有插件。**

用户在阅读比较结论后明确选择 Halo：熟悉 Java/Vue，接受其成熟主题上线方式，并再次确认当前网站仅包含商业计划书的四个模块。本报告据此定案；保留原始评分和其他候选证据，不为配合结论修改分数。PoC 验证 Halo 的实现方案，不再默认进行两套 CMS 的平行建设。

最关键的三条理由：

1. **维护技术与团队能力一致。** Halo 支持 Java 插件和 Vue3 控制台扩展，用户已确认熟悉这两项技术。两人兼职、外包后需要自行接手，减少新语言的长期维护负担是合理的决策依据；仍需学习 Halo 的响应式和资源模型。
2. **现成内容系统配合小型扩展可以覆盖当前四模块。** Halo 提供文章、草稿、发布、媒体和主题；工具、方案、测评记录、榜单快照通过自有插件管理。首版采用人工录入和基础筛选，不立即部署复杂采集或计算平台。主题是 UI 起点，数据表格、筛选与对比仍需要适配，不能宣称安装主题即完成全站。
3. **复杂业务有明确的独立服务出口。** 公共 UUID、字段、导出格式和 API 契约独立定义，复杂采集、价格历史和榜单计算以后可交给 Spring Boot。既保留熟悉技术，也避免所有未来功能被绑进 Halo 内核或某个主题。

**Halo 已被选定，但实现尚未验证通过。** 原任务权重下 WordPress 与 Halo 仅差 0.10/5，权重变化即可逆转；用户的维护能力与明确偏好足以作出最终取舍。Halo 正文之外的字段草稿隔离、自定义资源发布流程、四模块主题适配、权限与备份恢复仍须通过 PoC。

配套文件：[推荐架构](./cms-architecture.md)、[PoC 验证计划](./cms-poc-plan.md)。

## 2. 调研边界与真实项目现状

### 2.1 已读取的材料

| 材料 | 对选型的作用 |
|---|---|
| `D:\tmp\CMS8AE6AF80E58BB8E884BCE4BB8A%A1.md` | 开源自部署、主题、草稿后台、自主扩展、去耦合、评分与八项最小 PoC 要求 |
| `D:\files\project\aihyb\ai测评网内部执行版商业计划书.md` | 四模块、两人兼职、首版外包、广告推广主线、自动化与未来移动端方向 |
| `D:\files\project\aihyb\plan.md` | SEO、4C8G 起步建议、首期不上注册、模型与智能体区分、后续论坛方向 |
| 本次用户补充 | 先用成熟模板上线，之后允许开发适配换外观；首版结构化录入、基础筛选和对比，自动采集逐步增加；最终选择 Halo，当前范围限定为四模块 |

目录扫描只有两份 Markdown 规划文件与一份商业计划 Word 文件；没有 README、应用代码、数据库、Docker 配置、依赖清单或 Git 仓库。父目录至项目根未找到 AGENTS.md。没有“现有 Spring Boot/Vue 工程必须复用”的证据；Java/Vue 是熟悉度偏好。

选型调研已检索官方文档、发行记录、许可证和部分源码/官方代码示例。当时没有安装候选；后续按用户授权已部署Halo并完成初步内容验证，见执行记录。其他候选未安装，两套主题、压力测试和完整恢复仍未执行。下文评分的“文档已核验”不等于项目组合实机通过；小样性能不外推为正式站能力。

商业计划 V0.3 比 `plan.md` 更适合解释当前收入方向；用户回复优先于文档。4,200 元是首轮现金预算建议，4C8G是原规划。后续用户将测试服务器从 `150.158.1.132` 改为 `5.231.253.4:22333`，实际2C6G；旧测试内容已清理，新环境已部署。初步实测与剩余门槛见 [执行记录](./cms-poc-progress.md)，候选评分仍保留调研时证据状态，不把小样结果外推为完整验证。

### 2.2 MUST / SHOULD / NICE-TO-HAVE

| 等级 | 要求 | 来源与说明 |
|---|---|---|
| MUST | 开源核心、可自部署、可研究修改源码；首版核心能力不依赖收费服务 | CMS 任务文档；本报告将“开源”按开放源代码许可理解，受限源码可用许可单独处理 |
| MUST | Web 编辑后台、媒体管理、保存草稿与发布 | CMS 任务文档；两名内容生产者需要可操作后台 |
| MUST | 四模块入口：能力测评、评分榜单、订阅/API 价格、技巧教程 | 商业计划；基础筛选和对比已由用户确认 |
| MUST | 结构化字段、后台 CRUD、服务端权限校验、公开 API 只读已发布数据 | CMS 文档与用户确认；不能只把表格硬编码到文章 |
| MUST | 文章与业务数据可导出；实体定义和业务逻辑不放主题；外部服务通过 API 接入 | CMS 文档；未来 Android 与独立服务需要持续可用的契约 |
| MUST | 完成八项最小 PoC，包括已发布版本和新草稿隔离、两套 UI、恢复升级、资源测量 | 这是选型验收门槛；预览等日常运营优先级仍可后续确认 |
| SHOULD | 成熟模板、手机适配、两套 UI 可经有限开发适配切换 | 用户确认；取消“后台一点切换即可让所有业务 UI 完全相同”的强假设 |
| SHOULD | SEO 友好的 HTML、稳定 URL、摘要、canonical、站点地图与结构化数据 | `plan.md` 明确要 SEO；具体搜索目标、SSR/SSG 比例待定 |
| SHOULD | 来源、核验日期、模型版本、过期资料标记、商业关系披露 | 商业计划中的内容可信度与维护要求；转为可校验字段 |
| SHOULD | 两人可维护、可 Docker 部署、升级回滚与备份可执行 | 团队现状与 CMS 文档；实际运行成本待测 |
| SHOULD | 历史版本恢复、可靠定时发布、采集先入草稿再人工审核 | 编辑体验和自动化方向；不假设已有多级审批需求 |
| NICE-TO-HAVE | 公开账户、订阅推送、论坛、课程、商业交易、国际化、全文搜索服务 | 均未确认首版需要；`plan.md` 明确首期不上注册，商业计划弱化课程 |
| NICE-TO-HAVE | Nuxt/Vue 全站前台、统一登录、Android App | 有扩展方向，不据此预建完整平台；无“必须 Vue 前台”的硬约束 |

**一票否决：** 首版必需能力强制收费或受托管绑定；匿名泄露草稿/可写业务数据；内容只能依赖某主题或闭源页面构建器恢复；不能完成数据导出及恢复；自定义模型无法在后台管理。已发布内容与新草稿不能隔离且无法在限定工时内修复，也否决该实现。

主题多寡不单独决定胜负。用户已接受适配后换外观，验证的是“业务实体不丢失，关键页面有正常展示”，不是任意两个主题安装后都无须配置。

## 3. 业务模型与 CMS 的职责

| 概念 | 首版所需字段/操作 | 推荐边界 |
|---|---|---|
| Article / Tutorial | 正文、摘要、任务分类、步骤、提示词、示例、关联工具、来源、披露 | CMS 原生文章；教程用分类和类型字段区分 |
| Tool | 公共 UUID、slug、厂商、模型/应用/智能体类型、版本、支持任务、官网 | 自有插件的结构化记录；以后迁业务服务 |
| SubscriptionPlan | 工具 UUID、币种、金额、周期、地区、税说明、免费额度、限制、来源、核验日期 | 首版人工管理当前方案；未知值与免费/不限量严格区分 |
| ProductReview | 工具、任务、具体版本、证据来源、测评条件、结论、样例、核验日期 | 首版结构化测评记录，关联长文；不把外部评分混成同一尺度 |
| LeaderboardSnapshot | 任务、时间、方法版本、条目引用、名次、理由、来源、编辑审核状态 | 首版编辑维护的榜单快照；自动计算以后放独立服务 |
| SourceReference / Asset | 原始链接、引用范围、授权/使用说明、核验时间；附件清单 | 首版字段或关联列表，不要求立即为每个概念创建独立服务/表 |
| PriceObservation / TestRun | 历史价格、额度实耗、采集批次、原始结果、任务环境 | 扩展时由业务服务维护；不能拿 CMS 编辑历史充当价格时间序列 |
| Campaign / ClickEvent / Order | 赞助披露、导流统计；未来订单支付 | 首版披露属于内容，点击统计独立；账户、佣金结算、交易确认后另建领域 |

“测评聚合”和“自动生成稿件/视频”需要来源与人工核验链路；并不意味着要把抓取、视频渲染、任务队列塞进 CMS。文章发布与榜单发布是两个动作，应明确展示版本和核验日期。

## 4. 候选版本、许可和功能核验

以下是 2026-10-13 官方页面能观察到的稳定版本/评估基线。发行页与在线文档可能不同步；实施前记录实际 tag、镜像 digest、依赖锁文件、插件/主题版本和对应 LICENSE，禁止使用浮动 `latest` 或 beta 作为生产基线。

| 候选 | 观察到的基线与证据 | 许可与功能边界 | 本轮位置 |
|---|---|---|---|
| WordPress | [7.1.3 官方发行记录](https://wordpress.org/news/)；[Docker 镜像标签](https://hub.docker.com/_/wordpress) | [GPLv2 或后续版本](https://wordpress.org/about/license/)；主题、插件另外核对；备选采用原生区块与自有字段，不依赖商业页面构建器 | 备选，当前不实施 |
| Halo | [v2.26.1 稳定版](https://github.com/halo-dev/halo/releases/tag/v2.26.1)；2.27 beta 不采用 | [社区源码 GPLv3](https://github.com/halo-dev/halo/blob/v2.26.1/LICENSE)；[社区/付费镜像不同](https://docs.halo.run/guide/install/docker-compose)，采用 `halohub/halo` 而非 `halo-pro` | 用户已定案，执行 PoC |
| Ghost | [v6.69.0](https://github.com/TryGhost/Ghost/releases/tag/v6.69.0) | [MIT 条款源码](https://raw.githubusercontent.com/TryGhost/Ghost/v6.69.0/LICENSE)；主题、托管及附属服务分开判断 | 出版场景候选，首版不选 |
| Payload | [v3.90.2 稳定分支；v4 为 beta](https://github.com/payloadcms/payload/releases) | [核心 MIT](https://github.com/payloadcms/payload/blob/main/LICENSE.md)；不把商业 SSO/Publishing Workflows 算入免费功能 | Headless 首要候选，当前不选 |
| Strapi | [v5.57.0](https://github.com/strapi/strapi/releases/tag/v5.57.0) | [社区部分 MIT，ee 另有许可](https://github.com/strapi/strapi/blob/develop/LICENSE)；Content History、Releases 为付费计划，Review Workflows 为 Enterprise | 当前不选 |
| Directus | [v12.5.0](https://github.com/directus/directus/releases/tag/v12.5.0) | [当前 MSCL-1.0-GPL](https://directus.com/license)，存在竞争用途与许可证保护功能限制，版本四年后有 GPL 授权；[Free core/付费限制](https://directus.com/docs/licensing/overview) | 按严格开源门槛淘汰 |
| Hexo | [v8.1.2](https://github.com/hexojs/hexo/releases/tag/v8.1.2) | [MIT 条款](https://raw.githubusercontent.com/hexojs/hexo/v8.1.2/LICENSE)；静态生成器，无同等原生编辑后台 | 单独作为 CMS 淘汰 |
| Drupal（新增一个候选） | [11.4.8 发行列表](https://www.drupal.org/project/drupal/releases) | [GPLv2 或后续版本](https://www.drupal.org/about/licensing)；核心工作流优势明确 | 功能合适、首版维护投入偏高 |

Directus 的旧 BSL/$5M 说法不能用于当前 v12 选型。当前源码许可与免费层、Studio 用户和集合计数都需按版本核对；“可以免费下载自部署”不等于满足本项目严格开源条件。若用户将条件改成“接受受限源码可用”，才重新纳入；不通过长期停留旧分支规避维护成本。

Drupal 官方已公告 [2026-10-14 将发布关键安全更新](https://www.drupal.org/psa-2026-10-12)。本报告不推测尚未公布的漏洞细节；如后续试用 Drupal，需要先核查该更新，不能把今日观察版本当作长期安装建议。

### 4.1 WordPress：优势明确，草稿和字段修订是最大验证点

官方支持插件注册自定义文章类型和管理界面，且明确建议将类型放插件，以便换主题后内容继续存在；通过 `show_in_rest` 开放相应 REST 能力。工具、方案和测评可以使用这一方式，但多实体强关系、复杂查询和历史时间序列不应无限扩张到 post/meta 模型。[类型注册](https://developer.wordpress.org/plugins/post-types/registering-custom-post-types/)、[REST 自定义内容](https://developer.wordpress.org/rest-api/extending-the-rest-api/adding-rest-api-support-for-custom-content-types/)

主题候选先用免费 [Twenty Twenty-Five](https://wordpress.org/themes/twentytwentyfive/) 与 [Twenty Twenty-Four](https://wordpress.org/themes/twentytwentyfour/) 建立兼容性基准，再选实际视觉模板。它们不是已经完成的 AI 价格/榜单成品：比较表、来源卡片和筛选仍需小量定制。外包合同须交付自有插件、模板与字段定义，不接受把业务实体写进主题。

原生历史修订不能据此认定满足完整“已发布内容继续挂着，新版本另存草稿”的工作流。PoC 可使用 [PublishPress Revisions 免费版](https://wordpress.org/plugins/revisionary/) 4.1.0 验证待审修订；官方说明免费版支持 WordPress core，而 ACF、Elementor 等兼容列在 Pro。**原生自定义 meta、关系引用、REST 自动化提交的完整修订覆盖仍未实测，属于高风险项。** 不以付费版兜底，也不以“复制文章换 slug”算通过。

人类后台使用 Cookie 与 REST nonce；自动化可使用低权限用户的 Application Password，经 HTTPS 调用，并单独限制发布权限。该凭据是 API 身份方式，不是 Spring 服务单点登录方案。[官方认证说明](https://developer.wordpress.org/rest-api/using-the-rest-api/authentication/)

定时发布/同步不能依赖访客请求触发的频率，采用受控调度执行到期任务。备份要同时涵盖数据库、uploads、插件/主题制品、配置与密钥恢复程序。[系统调度集成](https://developer.wordpress.org/plugins/cron/hooking-wp-cron-into-the-system-task-scheduler/)、[官方 Docker 配置](https://hub.docker.com/_/wordpress)

### 4.2 Halo Core/API/Plugin：可以做业务，但需要遵守资源与响应式边界

**Core 的现成内容能力：** 当前用户文档列出保存、预览、发布、定时发布、分类、标签、附件等。稳定 tag 的 `Post` 源码包含 `headSnapshot`、`releaseSnapshot` 与 `baseSnapshot`，提供区分最新正文草稿与已发布正文的模型基础；这并不自动证明标题、分类、自定义业务字段都跟随正文隔离。[文章文档](https://docs.halo.run/guide/use/posts)、[v2.26.1 Post 源码](https://github.com/halo-dev/halo/blob/v2.26.1/api/src/main/java/run/halo/app/core/extension/content/Post.java)

**自定义模型与 CRUD：** 插件可继承 `AbstractExtension`，声明 GVK、注册 Scheme 和查询索引；注册后自动生成 `/apis/<group>/<version>/<plural>` 的 GET/POST/PUT/DELETE。它不是“只能代理第三方数据”的博客插件体系。但需要单独设计发布状态、版本、输入校验和并发处理；自定义类型不自动获得文章完整编辑工作流。[模型说明](https://docs.halo.run/developer-guide/plugin/api-reference/server/extension)

**后台扩展：** Console 插件 UI 使用 Vue3，可增加菜单、路由和管理页面；仍需写表单/列表，而非声明 Java 类后自动得到完整业务后台。[UI 介绍](https://docs.halo.run/developer-guide/plugin/basics/ui/intro)

**API 与权限：** 资源 CRUD 与聚合动作使用不同接口；自定义接口 group 可以面向 Console、UC 或公开调用。公开 group 不会自动授权匿名请求，需 RoleTemplate 和最小权限。自动生成资源 CRUD 不应把含 draft 的资源集合整类开放给匿名；公开聚合端点必须在服务端筛选 published。具体路径与认证以目标版本 OpenAPI 为准，PAT 仅服务端保存。[自定义端点](https://docs.halo.run/developer-guide/plugin/api-reference/server/custom-endpoints)、[API 权限](https://docs.halo.run/developer-guide/plugin/security/role-template)、[认证说明](https://docs.halo.run/developer-guide/restful-api/introduction)

**外部数据桥接：** WebClient 实时请求可转换为展示 DTO，缓存/超时降级；定时同步写入草稿或插件资源，并按外部 ID、版本去重。Finder 用于 Thymeleaf 渲染，不是浏览器 API。复杂抓取和计算可交给外部服务，插件负责边界适配。[响应式开发](https://docs.halo.run/developer-guide/plugin/basics/server/reactive-development)、[定时任务](https://docs.halo.run/developer-guide/plugin/basics/server/scheduled-tasks)

**主题边界：** Halo 常规主题为 Thymeleaf；独立 Nuxt/Vue 前台不能当作相同规格主题直接安装。插件可提供默认模板和 Finder，2.26+ 支持布局契约，未适配主题时存在 fallback 布局；仍需验证菜单、样式、SEO 和资源。不能把当前博客主题存在视为四模块 UI 已做好。[插件与主题集成](https://docs.halo.run/developer-guide/plugin/theme-integration)、[默认 Earth 主题源码](https://github.com/halo-dev/theme-earth)、[应用市场](https://www.halo.run/store)

**数据层边界：** 查询依赖模型索引；生命周期、资源版本和响应式运行约束不能等价理解为自由嵌入 Spring MVC/JPA/MyBatis 应用。本地私有文件/数据库的备份与迁移由插件负责，官方明确不自动理解插件私有 schema。[存储边界](https://docs.halo.run/developer-guide/plugin/basics/server/data-storage)

因此 Halo 具备成为主选的技术基础，并非仅因使用 Java 而入选；用户已据维护能力与上线方式选择它。目前四模块没有必须依赖 Halo 付费商城的需求。

### 4.3 Ghost / Payload / Strapi / Directus / Hexo / Drupal

| 候选 | 官方核验到的优势 | 本项目的代价与未验证项 |
|---|---|---|
| Ghost | 出版、主题、定时发布、Post history；Content API 和 Admin API 分开 | 原生文章/页面/标签体系不等于任意 ProductReview 与价格实体后台。需要外置后台与业务服务。Docker 官方新安装路径仍标为 preview；不把历史记录当完整待发布修改链路。[API](https://docs.ghost.org/content-api)、[Admin API](https://docs.ghost.org/admin-api)、[历史](https://ghost.org/changelog/post-history/)、[Docker](https://docs.ghost.org/install/docker) |
| Payload | TypeScript 模型、后台、API、可配置版本/草稿，明确支持已发布版本外另存新草稿；可用 PostgreSQL | 3.x CMS/后台引入 Next.js，公众前台可以另选，但不能消除 React/Next 的维护。模板是脚手架，不是一键主题。预览与权限需实现；Local API 默认跳过权限，访问者路径必须显式启用检查。[安装](https://payloadcms.com/docs/getting-started/installation)、[版本](https://payloadcms.com/docs/versions/overview)、[草稿](https://payloadcms.com/docs/versions/drafts)、[Local API](https://payloadcms.com/docs/local-api/access-control) |
| Strapi | 内容类型、关系、REST/GraphQL、免费 Draft & Publish；免费基础全屏预览 | 历史修订和 Releases 付费、Review Workflows 企业版，且 Content History 文档说明仅后台编辑生成历史，API/定时任务修改不覆盖。与自动化维护方向存在额外适配成本。前台及主题策略需自建。[草稿](https://docs.strapi.io/cms/features/draft-and-publish)、[历史](https://docs.strapi.io/cms/features/content-history)、[Releases](https://docs.strapi.io/cms/features/releases)、[预览](https://docs.strapi.io/cms/features/preview) |
| Directus | 数据模型、字段与行权限、内容版本、Docker 自部署 | 版本晋升不是自动等于完整文章发布产品；公众筛选需配置，许可及集合/席位上限独立核查。严格开源门槛下不继续 PoC。[权限](https://directus.com/docs/guides/auth/access-control)、[版本](https://directus.com/docs/guides/content/content-versioning)、[自部署](https://directus.com/docs/self-hosting/overview) |
| Hexo | 静态生成与主题生态，文件内容便于迁移 | 原生 CLI/Git 编辑流程不能满足多用户 Web 草稿和业务后台。外挂一个 CMS 后应按外挂 CMS 重新评估，不能继续当作单 CMS 成本。[官方文档](https://hexo.io/docs/) |
| Drupal | 核心 Content Moderation 支持已发布内容与待审工作副本；自定义内容类型、字段和权限适合结构化内容 | 引入 PHP/Drupal 配置与主题开发体系，交接和维护需要专门经验；本轮没有对应人员或报价证明低成本。若以后多角色复杂编辑流程成为硬需求应重新评估。[核心工作流](https://www.drupal.org/docs/8/core/modules/content-moderation/overview)、[许可](https://www.drupal.org/about/licensing) |

## 5. 评分：证据与未知项同时保留

采用任务文档原始权重，不人为调整到某方案获胜。分数 0–5，5 表示高度适合本项目，0 表示不满足。原始加权分保留三位小数只为可复算，不代表测量精度。

标记：**D**＝官方文档已核验；**S**＝源码字段/许可或官方代码示例已核验；**J**＝基于需求的判断；**U**＝需要实机/团队试用验证。所有格子的分数都是判断；D/S 只说明存在证据，不能证明本项目组合已经通过。

| 候选 | UI 20% | 扩展 25% | 草稿后台 15% | 技术栈 15% | 部署成本 10% | 迁移 10% | 安全健康 5% | 加权分 |
|---|---|---|---|---|---|---|---|---|
| WordPress | 4.5 D/U [W1] | 4 S/U [W2] | 4 D/U [W3] | 2.5 J/U [L] | 4 D/U [W4] | 4 D/U [W2] | 4 D/U [W5] | **3.875** |
| Halo | 3 D/U [H1] | 4 S/U [H2] | 4 D/S/U [H3] | 4.5 D/J/U [H4,L] | 3.5 D/U [H5] | 3.5 D/U [H6] | 4 D/U [H7] | **3.775** |
| Payload | 2.5 D/U [P1] | 4.5 D/U [P2] | 4.5 D/U [P3] | 2.5 D/J/U [P1,L] | 3 D/U [P4] | 4 D/U [P2] | 4 S/D/U [P5] | **3.575** |
| Drupal | 3 D/J/U [R1] | 4.5 D/U [R2] | 4.5 D/U [R2] | 2 J/U [L] | 3 J/U [R1] | 3.5 D/J/U [R1] | 4 D/U [R3] | **3.550** |
| Directus | 2 D/J/U [D1] | 4.5 D/U [D2] | 3.5 D/U [D3] | 3 J/U [L] | 3.5 D/U [D1] | 4 D/J/U [D2] | 2 S/D/U [D4] | 3.350，门槛淘汰 |
| Strapi | 2.5 D/J/U [T1] | 4 D/U [T2] | 3 D/U [T3] | 2.5 J/U [L] | 3.5 D/U [T1] | 3.5 D/J/U [T2] | 4 S/D/U [T4] | **3.225** |
| Ghost | 4 D/U [G1] | 2 D/J/U [G2] | 4 D/U [G3] | 2.5 J/U [L] | 4 D/U [G4] | 3.5 D/J/U [G2] | 4 S/D/U [G5] | **3.225** |
| Hexo | 4 D/U [X1] | 1.5 D/J/U [X2] | 1 D/J/U [X2] | 3 J/U [L] | 4.5 D/J/U [X2] | 4 D/J/U [X2] | 4 S/D/U [X3] | 2.825，门槛淘汰 |

公式：`Σ(维度分 × 权重)`；如需百分制，乘以 20。安全健康分不是“没有漏洞”的断言，仅综合许可清晰度、可见稳定发行与更新机制；未做完整依赖漏洞审计。

每个维度的主要未知项：UI＝手机/表格/代码块/两套主题兼容；扩展＝后台操作工时、索引/关系/并发；草稿＝正文与 meta/关系均隔离；技术栈＝实际接手工时；部署＝CPU、内存、冷启动；迁移＝脱离 CMS 的导出恢复；安全＝本项目配置下的权限与升级。未测量的“性能好”未计作已验证事实。

### 5.1 评分依据索引

- **[L]**：第 2 节三份本地文档与本次用户确认；没有已有工程，因此只评语言学习/接手成本，不给“代码复用”加分。
- **[W1]**：[主题目录](https://wordpress.org/themes/)、[TT5](https://wordpress.org/themes/twentytwentyfive/)、[TT4](https://wordpress.org/themes/twentytwentyfour/)。数量不作精确统计，业务组件仍待适配。
- **[W2]**：[插件型 CPT 与可迁移建议](https://developer.wordpress.org/plugins/post-types/registering-custom-post-types/)、[REST 字段与类型](https://developer.wordpress.org/rest-api/extending-the-rest-api/adding-rest-api-support-for-custom-content-types/)。关系模型与实际导出需要自有契约。
- **[W3]**：[待审修订免费插件说明及 Pro 边界](https://wordpress.org/plugins/revisionary/)。自有字段和 API 提交不作支持保证。
- **[W4]**：[官方镜像与 Compose 示例](https://hub.docker.com/_/wordpress)、[系统调度](https://developer.wordpress.org/plugins/cron/hooking-wp-cron-into-the-system-task-scheduler/)。4C8G/2C4G 均未测。
- **[W5]**：[许可](https://wordpress.org/about/license/)、[发行与安全更新](https://wordpress.org/news/category/releases/)、[API 认证](https://developer.wordpress.org/rest-api/using-the-rest-api/authentication/)。插件维护面另算。
- **[H1]**：[市场](https://www.halo.run/store)、[主题集成](https://docs.halo.run/developer-guide/plugin/theme-integration)。博客模板与数据门户的适配差异导致 3 分。
- **[H2]**：[自定义资源](https://docs.halo.run/developer-guide/plugin/api-reference/server/extension)、[自定义 API](https://docs.halo.run/developer-guide/plugin/api-reference/server/custom-endpoints)。关系/事务另测。
- **[H3]**：[编辑发布](https://docs.halo.run/guide/use/posts)、[Post 快照源码](https://github.com/halo-dev/halo/blob/v2.26.1/api/src/main/java/run/halo/app/core/extension/content/Post.java)。正文基础明确，metadata 和自定义资源另测。
- **[H4]**：[Vue3 插件 UI](https://docs.halo.run/developer-guide/plugin/basics/ui/intro)、[WebFlux 开发](https://docs.halo.run/developer-guide/plugin/basics/server/reactive-development)。熟悉 Java 不等于熟悉 Halo。
- **[H5]**：[部署](https://docs.halo.run/guide/install/docker-compose)。JVM heap 配置不是整机占用实测。
- **[H6]**：[存储和备份责任](https://docs.halo.run/developer-guide/plugin/basics/server/data-storage)、[API](https://docs.halo.run/developer-guide/restful-api/introduction)。插件 GVK/Finder 等需迁移适配。
- **[H7]**：[稳定发行](https://github.com/halo-dev/halo/releases)、[许可证](https://github.com/halo-dev/halo/blob/v2.26.1/LICENSE)、[权限](https://docs.halo.run/developer-guide/plugin/security/role-template)。
- **[P1]**：[安装与模板](https://payloadcms.com/docs/getting-started/installation)。模板脚手架和 Next 后台决定 UI/栈成本。
- **[P2]**：[访问控制](https://payloadcms.com/docs/access-control/overview)、[PostgreSQL](https://payloadcms.com/docs/database/postgres)。模型可控，但不是跨 CMS 通用 schema。
- **[P3]**：[版本](https://payloadcms.com/docs/versions/overview)、[草稿](https://payloadcms.com/docs/versions/drafts)、[预览](https://payloadcms.com/docs/admin/preview)。配置正确性仍需验证。
- **[P4]**：[安装要求](https://payloadcms.com/docs/getting-started/installation)、[Jobs](https://payloadcms.com/docs/jobs-queue/overview)。构建峰值/后台任务运行方式另测。
- **[P5]**：[许可](https://github.com/payloadcms/payload/blob/main/LICENSE.md)、[稳定与 beta 发行](https://github.com/payloadcms/payload/releases)、[Local API 权限](https://payloadcms.com/docs/local-api/access-control)。
- **[T1]**：[安装含 Docker 指南入口](https://docs.strapi.io/cms/installation)。独立前台/模板成本为判断。
- **[T2]**：[内容类型入口与 API](https://docs.strapi.io/cms/features/draft-and-publish)、[GraphQL](https://docs.strapi.io/cms/api/graphql)。关系/API 模型需要迁移映射。
- **[T3]**：[草稿](https://docs.strapi.io/cms/features/draft-and-publish)、[历史限制](https://docs.strapi.io/cms/features/content-history)、[预览分层](https://docs.strapi.io/cms/features/preview)。付费及自动化历史缺口扣分。
- **[T4]**：[许可](https://github.com/strapi/strapi/blob/develop/LICENSE)、[发行](https://github.com/strapi/strapi/releases)。
- **[D1]**：[自部署](https://directus.com/docs/self-hosting/overview)。前台另建；未验证实际免费层配置。
- **[D2]**：[行/字段权限及数据操作](https://directus.com/docs/guides/auth/access-control)。数据归属可控不代表可共享 schema 无成本。
- **[D3]**：[内容版本](https://directus.com/docs/guides/content/content-versioning)。编辑流程仍需组合。
- **[D4]**：[MSCL](https://directus.com/license)、[源码许可](https://github.com/directus/directus/blob/main/license)、[许可/集合计数](https://directus.com/docs/licensing/overview)、[发行](https://github.com/directus/directus/releases)。严格开源硬门槛先于得分。
- **[G1]**：[主题](https://ghost.org/themes/)。内容媒体适用，价格表与数据管理未覆盖。
- **[G2]**：[Content API](https://docs.ghost.org/content-api)、[Admin API](https://docs.ghost.org/admin-api)。扩展为外置服务的成本计入。
- **[G3]**：[Post history](https://ghost.org/changelog/post-history/)、[定时发布](https://ghost.org/help/publishing-content/)。不宣称任意业务实体版本原生支持。
- **[G4]**：[Docker preview](https://docs.ghost.org/install/docker)。此项为初步成本判断，不是官方整机性能承诺。
- **[G5]**：[源码许可](https://raw.githubusercontent.com/TryGhost/Ghost/v6.69.0/LICENSE)、[发行](https://github.com/TryGhost/Ghost/releases)。
- **[X1]**：[主题目录](https://hexo.io/themes/)。
- **[X2]**：[CLI/文件生成工作流](https://hexo.io/docs/)。静态生成成本低与后台缺失分别判断。
- **[X3]**：[许可](https://raw.githubusercontent.com/hexojs/hexo/v8.1.2/LICENSE)、[发行](https://github.com/hexojs/hexo/releases)。
- **[R1]**：[Drupal 项目与源码入口](https://www.drupal.org/project/drupal)。主题、配置、维护成本为判断，尚无项目对应模板实测。
- **[R2]**：[内容类型工作流/权限](https://www.drupal.org/docs/8/core/modules/content-moderation/overview)。
- **[R3]**：[许可](https://www.drupal.org/about/licensing)、[发行](https://www.drupal.org/project/drupal/releases)、[当前安全公告](https://www.drupal.org/psa-2026-10-12)。

### 5.2 敏感性分析：不要把微小差距当定论

| 权重情景 | WordPress | Halo | Payload | 含义 |
|---|---:|---:|---:|---|
| 原任务权重：UI 20%、扩展 25% | 3.875 | 3.775 | 3.575 | 调研时 WordPress 小幅领先，不足以压过明确维护偏好 |
| UI 改 10%、扩展改 35%，其余不变 | 3.825 | 3.875 | 3.775 | Halo 反超；技术栈与扩展成本足以改变结果 |

保留原始权重，避免为了最终决定追改分数。用户明确选择 Halo，是在两个接近方案中优先考虑熟悉技术与后续接手；不代表用户正式批准了第二行权重。PoC 后用真实工时、UI 演示和资源记录验证实现，不保留未经验证的高分。

## 6. 三种任务架构与主选架构比较

| 架构 | 本项目适用度 | 主要代价 | 结论 |
|---|---|---|---|
| A：Halo 全站承载 | 人工测评、当前方案、编辑榜单、教程首版能尝试 | 插件后台与主题契约要开发；自动草稿/修订不是所有资源自带；复杂关系与历史数据边界需验证 | 选定的首版运行形态；限制在当前四模块 |
| B：Halo CMS + Spring Boot | 内容与复杂采集/计算分开，Java/Vue 适配较好 | 如果首期全部一起做，会提前承担两套后台、身份与运维；Halo 主题仍需适配 | 推荐演进架构；首期先插件，服务随实际需求引入 |
| C：Headless CMS + 独立前台 | 多终端、自由 UI、定制数据门户更直接 | 前台、预览、SEO、缓存、图片、部署都要交付；模板切换需工程支持 | 不是本轮最快的默认起点；如果首版升级为复杂产品平台，优先评 Payload |
| 备选：WordPress + 小插件 → 独立业务服务 | 符合人工管理、模板上线和外包接手 | PHP 适配、修订插件治理、自定义字段快照是主要风险 | 保留证据；当前不建设这套系统 |

不推荐首期同时启动 WordPress + Nuxt + Spring Boot + 独立身份服务 + 独立搜索 + 多数据库。先保留模块/契约，复杂度出现时再增加运行组件。

## 7. 其他候选的放弃原因、迁移成本与重新评估条件

| 方案 | 当前不主选的原因 | 以后何时重新评估 |
|---|---|---|
| WordPress（备选） | 原始评分接近，但用户优先选择熟悉的 Java/Vue 维护方式；不用模板数量的优势覆盖接手成本 | Halo 的简单编辑/主题方案经限时修复仍无法通过，而 WordPress 可低成本满足时再评 |
| Payload | 模型/草稿强，但需要维护 Next/React 后台和定制前台，首版模板交付优势尚未证明 | 多终端/复杂数据页面明确进入首版；团队愿意承担新栈并给出可维护样板 |
| Strapi | 前台另建，并存在修订、定时 Releases 与高级工作流许可成本；采集修改未自动进入 Content History | 这些高级功能不需要，或其适配成本在实测中可控 |
| Ghost | 定位出版强，价格/榜单数据后台需要再造 | 产品收敛成 Newsletter/订阅媒体，结构化比较弱化 |
| Directus | 当前受限许可不满足本轮严格开源门槛，免费层/许可功能有独立限制 | 用户明确调整许可条件后再评 |
| Hexo | 单独不满足编辑后台与业务 CRUD | 仅作为由 CMS 导出的静态前台生成器 |
| Drupal | 对工作流与内容建模合适，但本项目没有 Drupal 接手经验与交付成本依据 | 多角色/复杂审核成为刚需，有明确维护人员 |

**推荐处理顺序：** Halo PoC 出现问题先修复最小实现，并区分是插件缺陷、主题适配还是 CMS 能力边界。复杂查询/事务超出资源模型时优先抽离 Spring Boot；内容 UI 与编辑流程经限时修复仍不成立才重新评估 WordPress；若独立前端和复杂内容模型成为当前明确需求，再评 Payload。不得仅因未来可能扩展而提前更换已选 CMS。

迁移成本不能说成“一键”或“有 API 所以零成本”：

- **WP → Halo**：正文/slug/分类/附件映射，区块内容转中立 HTML/Markdown，CPT/meta → 自定义资源或业务库，重建主题与 Finder、权限和草稿历史映射；成本中高，UI 必须重做。
- **Halo → WordPress**：文章快照映射为正文、修订或独立归档，插件资源映射 CPT/meta 或业务库，重写主题、后台和权限；公开 UUID/slug 可以保留，Halo 控制器与 Finder 不可直接复用，成本中高。
- **WP/Halo → Payload + 独立前台**：迁文章、关系、版本、媒体，重写前台与预览；富文本 AST/区块转码是主要风险，成本高。
- **只抽离业务数据 → Spring Boot**：保留公共 UUID、导出契约与旧链接；只更换数据仓库适配器和管理入口，文章 CMS 无须同时迁移。成本中等且可分批降低。
- **换主题**：模型与数据不迁，但首页/页头/菜单/样式、组件布局和 SEO 需要回归；业务插件默认渲染是保底，不保证品牌外观一致。

所有成本是定性判断，PoC 以 50 条结构化记录、10 篇内容、媒体清单的真实迁移工时校正；历史草稿不能映射时须记录差异，禁止静默丢弃。

## 8. 已确认与待确认事项

**已确认：** 用户已选 Halo；当前仅四模块；首版先成熟模板，换外观可适配；人工结构化数据和基础对比；无现有生产工程；没有首期公开注册证据且旧规划明确不上注册。

**实施前仍需确认：** 实际网站预算与外包报价；生产服务器/托管区域与媒体容量（当前PoC已部署实际2C6G主机）；运营审核、定时更新及修订保留；数据规模与更新频率；搜索体验；移动端未来是否启动；来源授权；Halo 插件维护负责人。未确认项不视为批准收费或生产部署。

**当前高风险未决项：** Halo metadata 与自定义业务资源发布隔离，两套 UI 的完整业务展示，公开读接口的字段/状态过滤，真实运行占用与恢复时间。它们已转为 PoC 门槛。WordPress 自有字段修订只作为备选风险保留，当前不执行其验证。
