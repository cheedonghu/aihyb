# 主题源码与校验

`shared/` 是 AI好用榜定制源文件，`adapters/` 是部署文件和 SHA-256 清单。`upstream/` 保留 Earth 1.15.3、Anatole 2.1.0 的固定源码及上游许可，供完整生成适配时使用；打包文件及历史截图在仓库外归档。

常规修改 Earth 共享模板后，在仓库根目录运行：

```powershell
python themes/scripts/update-portal-adapters.py --baseline themes/baselines/test.json
python scripts/check-repository.py
```

此命令只更新本地文件，不连接网站。需要针对生产保留可接受的前一版本时显式选择 `production.json`；新适配的哈希相同不代表两个环境都已部署。生产基线目前仅保留有独立记录的备案页脚指纹，其他文件修改前需先核对并补齐对应生产基线；不能用测试指纹代替生产核验。缺少必要基线时更新脚本会拒绝继续。

`prepare-theme-adapters.py` 用固定上游源码完整重建适配，会重写适配文件和清单；只在需要重新生成时使用，执行前保留本地改动并核对输出。

发布使用 [部署脚本](../deploy/scripts/deploy-theme-adapters.py) 的全量哈希校验与备份流程。脚本拒绝覆盖与原版、已知前一版本及目标版本均不匹配的文件，任何文件不匹配时均不开始写入。`--check` 只验证，实际发布另行执行。

运行时新部署记录位于目标实例的 `runtime/theme-adapters.json`。确认目标版本后，人工审查并只同步文件指纹到 `baselines/`；备份路径、截图、日志和请求结果不作为源码依赖。
