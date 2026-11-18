# 外部数据接入与开放权重模型榜

Python 采集并转换来源，Halo 业务插件从本地目录读取最新有效批次，更新榜单栏目、首页入口和固定详情页。普通投稿继续使用稿件审核流程。ModelDial 与开放权重模型榜共用来源清单、快照和校验规则。[命令行工具](../scripts/import_external.py)

## 自动更新模块数据

从仓库根目录执行下列命令，可以在本机采集并更新两个来源。每个命令完成一次同步，已有的自动化任务可直接调用它们。

```powershell
python scripts/import_external.py sync open-models --data-dir build/module-boards
python scripts/import_external.py sync modeldial --data-dir build/module-boards
```

开放权重模型榜默认采集能力与硬件资料。ModelDial 的五个维度共用一个批次文件，五份全部有效后一次切换。分数或实测记录变化时生成新版本，单纯重复取得同一组数据会跳过。采集或解析失败时，命令报告原因，上一版继续展示。插件逐批核对文件哈希、稿件结构及榜单身份，遇到损坏的新文件时读取最近的有效版本，重启后也能回退。

插件通过 `AIHYB_MODULE_DATA_DIR` 选择数据目录，采集端需要对该目录有写权限。项目 Compose 将宿主机 `./data/module-boards` 只读挂载到 Halo 的 `/var/lib/aihyb/module-boards`。对接某个环境时，将 `--data-dir` 指向该环境的宿主机目录。本地生成的 `build/module-boards` 只用于本地验证。首次启用这份 Compose 配置时需要让目标 Halo 容器加载新挂载与环境变量，之后更新数据只需运行同步命令。[Compose 模板](../deploy/compose.yaml)

联网采集保存在 `_sync/<来源>/snapshots`，每次同步的结果写入 `reports`。`published` 表示新批次已经写入，`unchanged` 表示继续用原批次，`failed` 的报告记录拒绝原因，命令退出码为 2。若文件已可见而系统无法确认写入已持久化，命令返回 `published-durability-unconfirmed` 和退出码 3。此时检查存储和报告中的版本文件。若仅同步报告写入失败，命令会输出警告及已生效的批次路径。对每个参与计算的文件，程序记录实际取得时间与哈希。回放可能覆盖较新的来源数据时会拒绝更新。`observations` 中的记录用于这项检查，应随批次一起备份。记录缺失时从同一备份恢复。若插件日志报告新批次校验失败，按来源和文件名对照同步报告、快照与上一有效批次。[备份和恢复](deployment.md)

已有完整快照可以直接重放，无需重新联网。

```powershell
python scripts/import_external.py sync modeldial --snapshot-dir build/saved-modeldial --data-dir build/module-boards
```

开放权重模型榜的固定页面是 `/aihyb/boards/auto-open-models`。ModelDial 的固定页面使用 `/aihyb/boards/auto-modeldial-` 加 `backend`、`frontend`、`knowledge`、`aesthetic` 或 `maintainability`。每个地址展示该榜最新有效批次。该来源首次同步成功后，栏目中同口径的旧版导入快照由当前榜接替。旧稿依然可以通过历史地址访问。公开 JSON 接口使用例如 `/apis/api.aihyb.run/v1alpha1/contents/module:auto-open-models` 的标识，与普通稿件分别寻址。

## 离线生成可审核的独立快照

从仓库根目录运行。目录用本次采集的独立名称，`build/` 已被 Git 忽略。保留首次生成时间以便按相同参数重放。

```powershell
$batch = Get-Date -Format 'yyyyMMdd-HHmmss'
$root = "build/external-sources/$batch"
python scripts/import_external.py fetch open-models --with-hardware --snapshot-dir "$root/sources"
if ($LASTEXITCODE -ne 0) { throw '来源采集失败，请查看上游错误。' }
$generated = [DateTimeOffset]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz')
python scripts/import_external.py convert open-models --snapshot-dir "$root/sources" --output-dir "$root/drafts" --generated-at $generated
```

`fetch` 通过固定的公开 HTTPS 地址获取 Epoch 数据包、Qwen 实测页、官方模型及 GGUF 文件清单。`--with-hardware` 请求完整配置，省略时只生成能力数据。每次成功采集保留原始字节及 `snapshot.json`，记录每份资料的地址、实际取得时间、字节数和 SHA-256。`convert` 在本地核对快照，也接受显式取得的本地文件，参数见 `convert --help`。输出目录可选仓库外或 Git 忽略路径，同名不同内容会被拒绝。离线回放使用本地文件系统，已挂载卷或映射盘由操作方确认位置。快照完成前会按实际转换规则检查需要使用的资料，包括 ECI 表的结构、大小及 ZIP 成员 CRC。

`open-models.draft.json` 是待审稿件，`open-models.metadata.json` 是编辑核对用的输入哈希、能力和实测覆盖数、缺分模型及稿件哈希。仅将前者通过现有 `POST /apis/console.api.aihyb.run/v1alpha1/content-packages` 接收为草稿。审核者可在预览核对分数、显存档位和引用，再依次批准、发布。相同 ECI 表的补充资料更新属于同一榜单快照的新修订，已存在的条目须按当前草稿的 `base_revision` 提交。新一期 ECI 表会生成新的快照。提交身份、权限与冲突处理见[稿件协议](../contracts/README.md)。

将上述命令的适配器改成 `modeldial` 并去掉 `--with-hardware` 即可获取五维榜单稿件。已下载 overview 的编辑可继续使用原入口和已有参数。[ModelDial 导入说明](modeldial-import.md)

## 数据覆盖与展示口径

| 资料 | 进入榜单的条件 | 编辑核对项 |
| --- | --- | --- |
| [Epoch AI 能力指数](https://epoch.ai/benchmarks/use-this-data) | 只取 ECI 表的 `Open weights` 且有分数的模型。按具体模型版本匹配，以本期 ECI 降序排列，同分并列 | 核对公开署名与 CC BY 4.0 链接。本榜只使用 Epoch 指数，保留原始分及取得时间 |
| [Hugging Face 官方组织](https://huggingface.co/Qwen) | 已对齐官方仓库的 Qwen3-8B、14B、32B、30B-A3B | 模型卡提供许可证。下载大小取同版本官方 GGUF 仓库中精确匹配的 Q4_K_M 文件字节数。引用固定到提交 |
| [Qwen 推理实测](https://qwen.readthedocs.io/en/latest/getting_started/speed_benchmark.html) | 对上述四个型号取 Transformers 实测表、单请求、输入 6,144、生成 2,048 token 的记录 | 保留 GPU 型号、数量、框架、量化方式、原始显存 MB、证据类型和来源 |
| [LocalScore](https://www.localscore.ai/) | 提供社区单机速度和首字延迟的外链参考 | 社区记录有具体型号、工作负载、量化和来源时，可在稿件列为社区实测。批量导入需要先确认数据许可 |

显存档位为 8、12、16、24 GB。同一模型只要有一条标准工作负载下的单卡实测配置符合预算，就保留该行以及原始名次。按来源原单位换算，8 GB 档要求 `memory_mb <= 8000`，其余档位同理。模型的原始 ECI、各量化方式的实测显存，以及 GGUF 下载大小分别展示。没有可信硬件记录的模型仍能在“全部”查看。消费卡的可用显存、内核支持和速度需结合具体设备核对。

## 扩展来源

在[来源清单](../scripts/external_sources/sources.py)登记固定地址、规范文件名和大小上限，在[适配器目录](../scripts/external_sources/)加入纯转换函数，并接入统一命令。接入其它模块时可以直接复用快照和输出保护，产出对应 `kind` 的标准稿件。新增模型映射需核对 Epoch 模型名、仓库身份、量化文件及实测表，避免把蒸馏、指令、不同日期或不同尺寸的模型合并。虚构用例可运行 `python -m unittest discover -s scripts -p "test_*.py"`。
