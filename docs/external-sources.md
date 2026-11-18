# 外部数据接入与开放权重模型榜

编辑可在工作站用 Python 3.10+ 标准库采集来源，离线重放后将标准稿件送入已有草稿审核流程。插件和站点读取已发布版本。ModelDial 与开放权重模型榜共用来源清单、下载大小限制、快照哈希和输出保护。入口见[命令行工具](../scripts/import_external.py)。

## 取得一份可审核的榜单

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
