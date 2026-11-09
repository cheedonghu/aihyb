# ModelDial 离线成绩转换

本工具把用户已下载的 ModelDial overview JSON 转成现有协议的五份 `leaderboard` 标准稿件。它只生成本地草稿 JSON 和来源映射 metadata，不连接 Halo、不写服务器、不下载数据、不审核或发布稿件。实现与测试仅使用 Python 3.10+ 标准库；仓库内被忽略的输出目录检查还需本地 Git。

入口为 [modeldial_import.py](../scripts/modeldial_import.py)，离线虚构测试为 [test_modeldial_import.py](../scripts/test_modeldial_import.py)。稿件结构遵循现有 [统一稿件包协议](../contracts/README.md) 与 [Schema](../contracts/content-package-v1.schema.json)，没有新增协议字段。

## 已核对的来源结构

2026-11-09 实查 [API 说明](https://modeldial.com/agent)、[overview JSON](https://modeldial.com/data/overview.json)、[评测方法](https://modeldial.com/method) 与 [数据许可](https://modeldial.com/data-license)。ModelDial 在本工具中只是外部数据来源，本站展示模板不依赖其页面布局。

当前 overview 的核心结构是：

- `snapshot.batch_id`、`snapshot.published_at`、`snapshot.entries`：来源批次、发布时间和配置成绩。行未按排名排序；残留的 `rank` 和 `overall_score`、`three_axis_overall_score` 不用作本工具排名。
- `snapshot.entries[]`：`candidate_id`、`model_id`、`connection_id`、`reasoning_effort`、`score_policy` 以及五个 `*_score`。五轴为 `backend`、`frontend`、`knowledge`、`aesthetic`、`maintainability`。
- `publicationKey`、`viewKey`：公开数据的发布身份与视图身份。
- `qualityScores.schema_version`、`policy_id`、`base_publication_key`、`weights`、`entries`，以及 `qualityPublication.policy_id`、`configuration_sources`：五轴政策、质量分和各配置的来源发布身份。当前仅支持政策 `five-axis-b20-f25-k35-a10-m10-cube-half_up-v3`、结构版本 1 和 20/25/35/10/10 权重。权重仅用于识别当前来源口径，不计算综合分。
- `hiddenModelIds`：可能是模型 ID，也可能是完整候选 ID，分别过滤。
- `productEvaluations`、行内 `productEvaluation`、`product_kind` 和 `api_model_ranking_eligible`：产品/Bot 记录的排除信息。当前产品评测必须是对象，包含非空 `product_id` 与 `evaluation_id`，且 `kind=external_product_evaluation`；行内评测身份还必须匹配该行模型与候选。`productEvaluation` 字段一旦出现，不能为 `null`、数组或缺少上述身份字段。`api_model_ranking_eligible` 可以缺省，出现时必须为 JSON 布尔值；`product_kind` 出现时仅接受当前已知的 `model`、`bot`，未知枚举、空值和错误类型都会拒绝。即使一行声明 `product_kind=model`，只要仍有产品评测记录，也不纳入模型榜。上述标记校验先于隐藏过滤，畸形元数据不会被静默忽略。
- `displayNames`、`evidence`：公开显示名称和可用的底层方法版本。未提供的底层版本不补写。

工具验证上述核心字段后使用来源最终分项成绩，尤其不会从审美或可维护性的原始子轴重新推算最终分。质量分必须与 `qualityScores.entries[].scores` 一致；质量来源政策和基础发布身份也必须一致。新增不影响当前映射的来源字段可以存在；核心结构或评分政策变化会拒绝，需先人工核对并更新转换器。

## 运行

用户自行取得 overview 文件，并明确提供本地输入路径、输出目录和带时区的真实记录时间。程序不接收 URL，也不会自动联网获取缺失材料。以下路径和时间仅为命令占位示例，不是仓库中保存的数据：

```powershell
python scripts/modeldial_import.py `
  --input D:/editorial/modeldial/overview.json `
  --output-dir D:/editorial/modeldial/drafts-20261109 `
  --retrieved-at 2026-11-09T12:00:00+08:00 `
  --generated-at 2026-11-09T12:05:00+08:00
```

也可显式选择仓库已忽略的目录，例如 `build/modeldial/drafts-20261109`。工具逐一检查所有输出文件必须不受 Git 跟踪且命中忽略规则；普通源码目录会被拒绝。仓库外目录无需 Git 忽略检查。不要把真实 overview、生成稿件、metadata 或输出报告加入 Git。

`--retrieved-at` 是实际取得该输入文件的时间，`--generated-at` 是本次草稿生成的记录时间，两者都必须明确提供 RFC 3339 时区。同一次离线重跑沿用原时间以获得相同字节和提交标识。来源的 `as_of` 与 `original_published_at` 始终取 `snapshot.published_at`，不以下载时间、转换时间或模型发布日期代替。快照发布时间也不解释为每个配置的实测时间。

成功输出包含六个 JSON 文件：五个轴各一份 `modeldial-轴名.draft.json`，以及一份 `modeldial-import.metadata.json`。所有验证在创建输出目录之前完成；写文件前统一检查已有同名文件。重复输入、相同参数和时间生成相同字节，已有相同文件显示 unchanged，已有不同文件会整体拒绝且保留本地改稿。工具不清空目录、不覆盖文件，也不自动删除先前快照。意外磁盘/权限故障可能中断写入；排除故障后用同样输入和时间重跑，可补齐缺少的文件。

## 分项映射与排名

| ModelDial 轴 | 稿件任务标签 | benchmark |
| --- | --- | --- |
| backend | programming | ModelDial/backend/政策 ID |
| frontend | programming | ModelDial/frontend/政策 ID |
| knowledge | research | ModelDial/knowledge/政策 ID |
| aesthetic | programming | ModelDial/aesthetic/政策 ID |
| maintainability | programming | ModelDial/maintainability/政策 ID |

新草稿面向用户的标题依次为“后端工程榜”“前端交互榜”“知识推理榜”“视觉审美榜”“可维护性榜”，摘要例如“对比各模型在后端工程方面的能力表现与排名。”，使用简洁能力导语。标题和摘要不要求用户先区分来源，不带 ModelDial 前缀、快照日期、原始配置或方法版本。来源署名、许可、批次与转换说明仍完整保留在公开正文说明中；结构化来源、模型身份与全部 provenance/metadata 字段继续保留，评分、排名、过滤校验和稳定 ID 的生成策略不变。

每份稿件只含该轴一项成绩，`score_unit=custom`、`higher_is_better=true`、`allow_ties=true`、`ranking_mode=score`。自定义评分不表示通过率。ModelDial 的加权综合分不映射到本站 `overall`，本站综合榜仍是独立的人工排序概念。

每个轴先过滤隐藏模型、隐藏候选和产品/Bot 评测，再排除该轴明确为 `null` 的配置；实际零分保留。对每个原始 `model_id`，仅选当前轴成绩最高的一个配置；相同模型不同版本不会合并。若最高分有多个配置，按原始 `connection_id`、`reasoning_effort`、`candidate_id` 的字典序确定唯一配置。模型按成绩降序，同分按模型 ID 和配置身份确定展示顺序，采用竞赛名次 `1,2,2,4`，不使用来源行顺序或旧 `rank`。

公开条目 `model_version` 保留原始 `model_id`，`reason` 保留原始思考档位、连接身份和候选 ID，正文也列出模型版本、档位与连接。显示名只用于 `tool_name`，不参与模型版本分组，不把显示档位别名替换成原始身份。每轴可能选中不同的档位或连接，未提供底层版本时明确说明。已提供的 `backendVersion`、`frontendVersion`、`qualityVersion` 保留在公开正文与 metadata；当前政策下不同配置仍可能带有不同底层版本，不能据此宣称做过同一批次的本站复测。

## 来源、去重和幂等

公开 `body_markdown` 写明 ModelDial、[Radar](https://modeldial.com/radar)、overview 链接、来源批次与发布时间、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 及数据许可说明，并说明进行了格式转换、筛选、按轴选配置和重新排名，分项成绩未修改。正文不暗示 ModelDial 认可本站，不制造推荐或独立核验结论。`sources.usage_note` 只是后台补充，公开署名不依赖它。工具不复制许可未覆盖的题目、评分材料、网页文案、图片或设计。

metadata 单独记录原始输入字节 SHA-256、来源批次/发布时间/publicationKey/viewKey/policy、排除原因、每轴缺项候选、所选模型/连接/原始档位/候选 ID、质量分来源发布身份、底层版本，以及输出稿件文件的 SHA-256、`content_id`、`submission_id` 和 `entry_id`。metadata 不属于稿件 Schema，不会被塞进未知字段。

- 同一个候选 ID 的相同规范化身份与成绩去重；同一候选 ID，或同一模型/连接/档位身份出现冲突成绩会拒绝。旧 rank、综合分等不用作比较的字段不参与候选成绩冲突判断，但原始文件哈希仍完整保留。
- `tool_id` 由原始模型 ID 派生；`entry_id` 由轴、模型、连接与档位派生，均使用 SHA-256 的稳定前缀，名称变化不会造成身份混淆。
- `content_id` 由轴和来源发布身份派生，因此新来源批次/发布身份对应新的榜单快照。`submission_id` 由完整草稿内容（含输入哈希和显式时间）派生，同一次转换重跑相同；内容改变则变化。metadata 还保留完整文件 SHA-256 供核对。
- 输出均为新稿件草稿包，不带 `base_revision`。本工具不读取后台回执，也不推测已有草稿修订。同一来源身份内容发生变更时可能保持 `content_id` 而变更 `submission_id`；以后人工接入导入流程时必须按现有协议明确处理当前草稿修订，不能把本地哈希当作后台乐观锁或跨账号幂等保证。

## 拒绝条件与验证

缺少来源身份/日期/模型版本/原始思考档位/连接、缺少必要过滤元数据、核心字段结构不符、不支持的评分政策、质量分/发布身份冲突、非法分数或冲突重复记录，都会以退出码 2 指明字段或配置并停止。缺项应明确写成 `null`，缺少五轴分数字段被视为结构不足而拒绝；某个配置在一个轴缺项只排除该轴，某轴完全没有可用模型则拒绝整个五轴批次。每轴超过现有 200 条、正文或序列化稿件超过现有协议长度/128 KiB 限制也会拒绝，不静默截断。重复 JSON 键、NaN/Infinity、尾随 JSON 和缺时区日期不接受。

输入上限为 16 MiB（16,777,216 字节），为当前数百 KiB 的 overview 保留增长空间，并限制本地解析的资源用量。CLI 最多读取上限加一个字节，超限立即拒绝；直接调用转换函数也会在 JSON 解析前检查长度。分数使用 Decimal 的数字元组和有效指数先检查最多 12 位有效小数及可表示长度，再移除尾零并格式化；类似 `1e-1000000000` 的极小非零指数直接拒绝，不展开为巨大字符串。实际零分与不改变数值的尾零可安全规范化。

运行离线测试与仓库检查：

```powershell
python -m unittest discover -s scripts -p test_modeldial_import.py -v
python scripts/check-repository.py
```

测试在临时目录构造虚构模型、分数、批次与哈希，不下载真实数据；覆盖 Schema、能力标题与简洁摘要、各轴配置选择、并列排名、隐藏与产品过滤、缺项与零分、最终质量分、五份草稿的完整公开许可署名、来源一致性、哈希与稳定 ID、重复和冲突、输出目录保护、改稿保护及 CLI 离线运行。
