# 本地开发与构建

需要 Java 21、Node.js >=22.12、Python 3 和 Git。Gradle Wrapper 随仓库保存，前端使用锁定的 pnpm 版本；首次构建需要访问公开依赖仓库。

## 常规验证

在仓库根目录运行：

```powershell
python scripts/check-repository.py
cd plugin-aihyb
$env:CI = 'true'
.\gradlew.bat build --no-daemon --console=plain
```

Linux/macOS 使用 `CI=true ./gradlew build --no-daemon --console=plain`。测试、类型检查及前端打包均由构建包含；单独检查前端可在 `plugin-aihyb/ui` 运行 `pnpm test:unit --run` 和 `pnpm build`。Node 版本及依赖锁以 `package.json`、`pnpm-lock.yaml` 为准。

离线媒体客户端验证可运行 `node plugin-aihyb/ui/scripts/verify-media-client.cjs`；它只用虚构的 PNG、MP4 和文本样例，结果写到被忽略的 `plugin-aihyb/build/reports/`。测试视频只是几字节的协议样例，不属于视频制作资料。

JAR 输出到 `plugin-aihyb/build/libs/`。Java 测试位于插件 `src/test`，Vue 测试随前端源码保存。构建、报告、依赖和缓存不提交；不要把运行报告用作源码依赖。

## 协议与客户端

`contracts/content-package-v1.schema.json` 是协议的维护入口，必须与插件资源中的同名文件一致。修改后运行 `python contracts/scripts/sync-schema.py`；默认仓库检查只对照内容，不写文件。`contracts/examples` 与 `contracts/fixtures/media` 只保留虚构材料。

`contracts/openapi/aihybApis.json` 是生成客户端使用的 API 快照，`plugin-aihyb/ui/src/api/generated` 中的 TypeScript 文件随源码提交。因此常规构建不依赖运行中的 Halo，也不访问生产后台。

确实修改 API 时，在隔离测试环境取得新的公开 API 描述并审查后更新快照。现有 Gradle 生成任务可用以下步骤离线生成客户端：

```powershell
cd plugin-aihyb
New-Item -ItemType Directory -Path build/api-docs/openapi/v3_0 -Force
Copy-Item ../contracts/openapi/aihybApis.json build/api-docs/openapi/v3_0/aihybApis.json
.\gradlew.bat generateApiClient -x generateOpenApiDocs -x processUiResources --no-daemon
```

更新快照和客户端需一起审查。只有显式刷新接口文档时才设置 `AIHYB_OPENAPI_URL`，私有请求头文件通过 `AIHYB_OPENAPI_HEADERS_FILE` 指定，禁止纳入版本管理；默认回环端口仍应指向隔离测试实例。

## 主题开发

修改 `themes/shared` 后，按 [主题说明](../themes/README.md) 更新适配器及哈希。本地校验不要求网站在线。主题发布另行明确测试或生产环境，使用带哈希保护与备份的部署脚本，禁止直接覆盖服务器主题目录。
