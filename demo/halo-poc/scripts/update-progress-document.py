"""Replace the current progress summary while retaining the dated native baseline appendix."""
from pathlib import Path
root=Path(__file__).resolve().parents[3]
path=root/'docs/cms-poc-progress.md'
old=path.read_text(encoding='utf-8')
historical=old[old.index('## 2026-10-13 原生文章历史基线'):]
summary='''# Halo PoC 执行记录

日期：2026-10-14。已完成本轮“原生文章入口＋少量稿件扩展＋完整版本保护”，以及 Earth 1.15.3 → Anatole 2.1.0 → Earth 的切换专项。完整 P1–P8 尚未全部通过。

当前服务器5.231.253.4:22333，独立目录 `/opt/aihyb-halo-poc`。Halo2.26.1、PostgreSQL17.11；Halo仅监听127.0.0.1:18090，数据库不映射主机端口，维持forwarding=0。原SSH、监控、Docker及系统网络/DNS/时间进程基线PID保持，未调整其他服务。

人工入口：[Halo 原生文章](http://127.0.0.1:18090/console/posts)。点击文章的“稿件数据与审核”核验来源、JSON与明确修订，再返回原生发布按钮。独立稿件菜单已移除。公开入口：[四模块](http://127.0.0.1:18090/aihyb)。当前主题为Earth。

正文Markdown、结构化JSON及来源仍使用统一稿件包；仅一个自动化接收接口。ContentRevision保存不可变整包；受管理原生Post原子维护草稿、批准、公开状态及交付日志。标题、摘要、正文快照与结构化发布修订一起切换。旧版本提交409、未批准发布409、直接写入旁路403。11项测试、完整构建、实际原生入口、权限矩阵和独立复核通过。

两主题均提供标准布局和四模块导航。冻结的7个稿件、134份修订、58份稿件正文快照在A→B→A中保持不变；四基准模块的公开A与待审B同时保留。两主题、两尺寸的36种页面组合均无整体横向溢出，宽表格单独滚动，代码、提示词、来源、图片、canonical、筛选和基础比较通过。可选搜索插件因偶发样式读取故障在此PoC内停用，文件和配置保留。

完整结果、实现边界、截图和可复现脚本见[原生适配与主题报告](./cms-native-adapter.md)。现有Schema与四种样例见[协议1.0](../demo/halo-poc/contracts/README.md)。旧插件阶段的HTTP回归、幂等、并发和独立数据库恢复证据保留于 `demo/halo-poc/evidence`；数据库恢复结果不能代替整实例恢复。

| PoC | 已完成或专项通过 | 仍需验证 |
|---|---|---|
| P1 | 原生入口及完整标题/摘要/正文/data隔离、批准/发布与旧版拒绝 | Feed/站点地图、正式缓存、定时发布 |
| P2 | 完整包、不可变修订、并发、重启持久化 | 删除/归档、共用字典、未引用修订回收、规模性能 |
| P3 | 原生操作入口、少量审核扩展、四模块展示、基础筛选/比较 | 两位运营试用、跨产品比较、全部字段与边界数据集 |
| P4 | 四类Schema、单接收、只入草稿、幂等与旧基础冲突 | 实际skills、强制断线故障注入、附件失败 |
| P5 | Earth→Anatole→Earth、两尺寸、导航/模板/来源/表格/代码/图片/分页/canonical | 任意其他主题需另适配；不作为负载结果 |
| P6 | 实际导入/编辑/审核/发布角色及匿名、UC写入、原始资源保护 | 私有附件、导出权限、凭据生命周期 |
| P7 | 私密备份、约11秒重启、插件升级，旧阶段独立空库恢复 | 全部文件与权限空实例恢复、API迁移、核心升级失败回滚 |
| P8 | 资源限制、少量空闲/页面/网络样本 | 规定数据量、20并发持续测试、运营稳定性与真实用户线路 |

对象存储、CDN、专用视频嵌入尚未接入；assets非空可入草稿但禁止发布。当前无撤稿、跨包原子发布及修订历史恢复的完整UI。匿名只读公开DTO；预览/后台private,no-store。JSON不要求建立新业务库或ES，性能结论仍需负载测量。

以下保留早期基线用于解释为什么需要完整版本保护，**不是当前适配器的行为**。

'''
path.write_text(summary+historical,encoding='utf-8')
print('Updated current progress; retained dated historical baseline')
