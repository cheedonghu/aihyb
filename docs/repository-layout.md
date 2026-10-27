# 仓库边界与资料保留

2026-10-27 按用户要求，将仓库定位为网站开发仓库。仅调整本地文件和 Git 当前版本，不改写 Git 历史，也不连接或修改线上环境。

仓库外保留目录是 `D:/files/project/aihyb-materials/2026-10-27-repository-cleanup/`，与本仓库位于同一个项目父目录。该目录含私密资料，不整体提交或公开分享。

| 原位置 | 整理后位置 |
| --- | --- |
| `demo/halo-poc/plugin-aihyb` | 仓库 `plugin-aihyb`；整理前源码副本另存归档 `original-source/plugin-aihyb` |
| `demo/halo-poc/themes` | 开发所需内容进入仓库 `themes`，原始目录保留在归档 `demo/halo-poc/themes` |
| `demo/halo-poc/contracts`、虚构样例 | 仓库 `contracts`，原件保留在归档 `demo/halo-poc` |
| `demo/halo-poc/content`、`evidence`、`secrets` | 归档 `demo/halo-poc` 下保持原相对位置 |
| `demo/ui-preview-v2`、`demo/_archive` | 归档 `demo` 下保持原相对位置 |
| `video` | 归档 `video`，包括环境、模型、原素材与成片 |
| 原 `deploy` | 归档 `deploy`，包括一次性迁移、修补脚本和私密备份 |
| 原 `docs` | 归档 `docs`，仓库改为当前开发与维护说明；设计偏好和任务分类决策保留 |
| 根目录 `build` | 归档 `build` |
| 插件及前端原构建输出 | 归档 `build-artifacts` |
| 品牌原图 | 原件归档 `logo源文件`，网站开发副本位于仓库 `assets/brand` |
| 商业计划、`plan.md`、Logo 压缩包 | 归档 `business-and-brand` |

归档另存 `inventory-before.jsonl`（原路径和大小）、`counts-before.json`、原 Git 状态/差异和整理前约定。所有移出的资料均保留；本地视频环境含绝对路径，归档不代表完成其迁移运行验证。

开发仓库保存源码、测试、虚构样例、接口协议、必要依赖来源/许可、部署模板与校验元数据。实际文章、媒体和商业资料独立保管；运行报告和截图通过外部归档或 CI 制品保存。

`themes/baselines` 从旧运行记录中提取必要的部署哈希，移除了报告字段；它们用于明确接受的历史文件版本，不能据此断言服务器当前状态。Feed API 提取哈希保存在插件 `libs` 中，属于第三方依赖的来源信息。

旧目录与视频文件从 Git 当前版本移出后仍可从历史查到，本次不做历史重写或垃圾清理。以后新增开发内容不再放入 `demo`，也不把资料归档重新拷回仓库。
