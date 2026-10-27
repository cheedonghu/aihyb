# AI好用榜网站插件

Halo 2.26 的业务插件，包含四模块、最新资讯、稿件审核与完整发布版本保护，以及 Vue 3 控制台扩展。Java 21、Node.js >=22.12；使用随仓库保存的 Gradle Wrapper 与 pnpm 锁文件。

```powershell
$env:CI = 'true'
.\gradlew.bat build --no-daemon --console=plain
```

构建产物位于 `build/libs/`。构建不连接网站，开发与客户端生成步骤见[本地开发](../docs/development.md)，稿件字段见[协议](../contracts/README.md)，源码职责见[网站架构](../docs/architecture.md)。

Java 测试位于 `src/test/`，控制台源码和测试位于 `ui/src/`。`libs/` 保存编译所需的 Feed API、许可与来源信息；生成 API 客户端随源码维护，构建输出和依赖不提交。

[GPL-3.0](LICENSE) © AIHYB
