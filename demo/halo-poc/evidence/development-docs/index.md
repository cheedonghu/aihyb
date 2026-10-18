> For AI agents: the complete documentation index is available at https://docs.halo.run/llms.txt, the full documentation bundle is available at https://docs.halo.run/llms-full.txt.

# 插件开发

## 介绍

### [介绍](/developer-guide/plugin/introduction.md)
## 准备工作

### [准备工作](/developer-guide/plugin/prepare.md)
## AI 辅助

### [AI 辅助](/developer-guide/plugin/ai.md)

- [提供文档上下文](/developer-guide/plugin/ai.md#提供文档上下文)
- [核对版本与源码](/developer-guide/plugin/ai.md#核对版本与源码)
- [生成前检查清单](/developer-guide/plugin/ai.md#生成前检查清单)
- [Agent Skill](/developer-guide/plugin/ai.md#agent-skill)
## 入门

### [入门](/developer-guide/plugin/hello-world.md)

- [创建插件项目](/developer-guide/plugin/hello-world.md#创建插件项目)
- [按需扩展项目](/developer-guide/plugin/hello-world.md#按需扩展项目)
- [运行插件](/developer-guide/plugin/hello-world.md#运行插件)
- [下一步](/developer-guide/plugin/hello-world.md#下一步)
## 案例和最佳实践

### [Todo List](/developer-guide/plugin/examples/todolist.md)

- [准备项目](/developer-guide/plugin/examples/todolist.md#准备项目)
- [定义 Todo 模型](/developer-guide/plugin/examples/todolist.md#定义-todo-模型)
- [注册模型](/developer-guide/plugin/examples/todolist.md#注册模型)
- [生成 API Client](/developer-guide/plugin/examples/todolist.md#生成-api-client)
- [添加 Console 菜单](/developer-guide/plugin/examples/todolist.md#添加-console-菜单)
- [实现 Todo 页面](/developer-guide/plugin/examples/todolist.md#实现-todo-页面)
- [运行并验证](/developer-guide/plugin/examples/todolist.md#运行并验证)
## 基础

### [插件项目结构](/developer-guide/plugin/basics/structure.md)

### [插件注册和配置](/developer-guide/plugin/basics/manifest.md)

- [字段详解](/developer-guide/plugin/basics/manifest.md#字段详解)
- [插件运行模式](/developer-guide/plugin/basics/manifest.md#插件运行模式)

### [DevTools](/developer-guide/plugin/basics/devtools.md)

- [安装](/developer-guide/plugin/basics/devtools.md#安装)
- [使用说明](/developer-guide/plugin/basics/devtools.md#使用说明)
- [配置](/developer-guide/plugin/basics/devtools.md#配置)
- [任务](/developer-guide/plugin/basics/devtools.md#任务)
- [调试后端代码](/developer-guide/plugin/basics/devtools.md#调试后端代码)
- [迁移](/developer-guide/plugin/basics/devtools.md#迁移)

### [服务端](/developer-guide/plugin/basics/server/index.md)

### [UI](/developer-guide/plugin/basics/ui/index.md)
## API 参考

### [服务端](/developer-guide/plugin/api-reference/server/index.md)

### [UI](/developer-guide/plugin/api-reference/ui/index.md)
## 扩展点和定制化

### [服务端](/developer-guide/plugin/extension-points/server/index.md)

### [UI](/developer-guide/plugin/extension-points/ui/index.md)
## 与其他插件交互

### [依赖其他插件](/developer-guide/plugin/interaction/dependency.md)

- [依赖声明方式](/developer-guide/plugin/interaction/dependency.md#依赖声明方式)
- [依赖检查和加载](/developer-guide/plugin/interaction/dependency.md#依赖检查和加载)
- [提供依赖的插件项目结构](/developer-guide/plugin/interaction/dependency.md#提供依赖的插件项目结构)
- [版本管理](/developer-guide/plugin/interaction/dependency.md#版本管理)
- [源码参考](/developer-guide/plugin/interaction/dependency.md#源码参考)

### [发布共享 Java API](/developer-guide/plugin/interaction/shared-java-api.md)

- [创建 API 模块](/developer-guide/plugin/interaction/shared-java-api.md#创建-api-模块)
- [项目结构](/developer-guide/plugin/interaction/shared-java-api.md#项目结构)
- [编写 API](/developer-guide/plugin/interaction/shared-java-api.md#编写-api)
- [配置实现和使用方](/developer-guide/plugin/interaction/shared-java-api.md#配置实现和使用方)
- [发布](/developer-guide/plugin/interaction/shared-java-api.md#发布)

### [事件共享](/developer-guide/plugin/interaction/shared-events.md)

- [监听 Halo 提供的共享事件](/developer-guide/plugin/interaction/shared-events.md#监听-halo-提供的共享事件)
- [发布自定义共享事件](/developer-guide/plugin/interaction/shared-events.md#发布自定义共享事件)
- [最佳实践](/developer-guide/plugin/interaction/shared-events.md#最佳实践)

### [插件如何被扩展](/developer-guide/plugin/interaction/making-plugin-extensible.md)

- [定义扩展点](/developer-guide/plugin/interaction/making-plugin-extensible.md#定义扩展点)
- [查找扩展](/developer-guide/plugin/interaction/making-plugin-extensible.md#查找扩展)
## 与主题集成

### [与主题集成](/developer-guide/plugin/theme-integration.md)

- [选择集成方式](/developer-guide/plugin/theme-integration.md#选择集成方式)
- [提供稳定的模板契约](/developer-guide/plugin/theme-integration.md#提供稳定的模板契约)
- [提供浏览器 API 和资源](/developer-guide/plugin/theme-integration.md#提供浏览器-api-和资源)
- [扩展最终页面输出](/developer-guide/plugin/theme-integration.md#扩展最终页面输出)
- [验证集成状态](/developer-guide/plugin/theme-integration.md#验证集成状态)
## 安全和权限管理

### [基于角色的权限控制](/developer-guide/plugin/security/rbac.md)

- [RBAC 的基本概念](/developer-guide/plugin/security/rbac.md#rbac-的基本概念)
- [什么是基于角色的权限控制](/developer-guide/plugin/security/rbac.md#什么是基于角色的权限控制)
- [Role 和 RoleBinding](/developer-guide/plugin/security/rbac.md#role-和-rolebinding)
- [RoleBinding 与角色的关联](/developer-guide/plugin/security/rbac.md#rolebinding-与角色的关联)
- [角色模板](/developer-guide/plugin/security/rbac.md#角色模板)
- [继承角色/角色模板](/developer-guide/plugin/security/rbac.md#继承角色角色模板)
- [聚合角色](/developer-guide/plugin/security/rbac.md#聚合角色)
- [隐藏角色模板](/developer-guide/plugin/security/rbac.md#隐藏角色模板)
- [Halo 中的特殊角色](/developer-guide/plugin/security/rbac.md#halo-中的特殊角色)
- [Halo 中的内置角色](/developer-guide/plugin/security/rbac.md#halo-中的内置角色)
- [参考](/developer-guide/plugin/security/rbac.md#参考)

### [API 权限控制](/developer-guide/plugin/security/role-template.md)

- [角色模板定义](/developer-guide/plugin/security/role-template.md#角色模板定义)
- [角色绑定](/developer-guide/plugin/security/role-template.md#角色绑定)

### [UI 权限控制](/developer-guide/plugin/security/ui-permission.md)

- [什么是 UI 权限控制](/developer-guide/plugin/security/ui-permission.md#什么是-ui-权限控制)
- [UI 权限控制的核心概念](/developer-guide/plugin/security/ui-permission.md#ui-权限控制的核心概念)
- [Halo 的 UI 权限控制](/developer-guide/plugin/security/ui-permission.md#halo-的-ui-权限控制)

### [敏感数据与出站请求](/developer-guide/plugin/security/outbound-http.md)

- [使用 Secret 保存敏感数据](/developer-guide/plugin/security/outbound-http.md#使用-secret-保存敏感数据)
- [限制出站目标](/developer-guide/plugin/security/outbound-http.md#限制出站目标)
- [对安全开关采用 fail closed](/developer-guide/plugin/security/outbound-http.md#对安全开关采用-fail-closed)
- [发布前检查](/developer-guide/plugin/security/outbound-http.md#发布前检查)
## 测试插件

### [测试插件](/developer-guide/plugin/testing.md)

- [选择验证层级](/developer-guide/plugin/testing.md#选择验证层级)
- [运行自动化检查](/developer-guide/plugin/testing.md#运行自动化检查)
- [在本地 Halo 中联调](/developer-guide/plugin/testing.md#在本地-halo-中联调)
- [验证兼容性和生命周期](/developer-guide/plugin/testing.md#验证兼容性和生命周期)
- [记录可复核结果](/developer-guide/plugin/testing.md#记录可复核结果)
## 常见问题

### [常见问题](/developer-guide/plugin/faq.md)

- [插件启动失败](/developer-guide/plugin/faq.md#startup-failure)
- [类加载与依赖冲突](/developer-guide/plugin/faq.md#dependency-conflict)
- [UI 扩展不加载](/developer-guide/plugin/faq.md#ui-not-loading)
- [权限配置不生效](/developer-guide/plugin/faq.md#permission-not-effective)
- [开发期热重载不生效](/developer-guide/plugin/faq.md#hot-reload)
## 插件发布验收清单

### [插件发布验收清单](/developer-guide/plugin/release-checklist.md)

- [构建发布制品](/developer-guide/plugin/release-checklist.md#构建发布制品)
- [检查 JAR 内容](/developer-guide/plugin/release-checklist.md#检查-jar-内容)
- [验证安装和升级](/developer-guide/plugin/release-checklist.md#验证安装和升级)
- [核对 CI/CD](/developer-guide/plugin/release-checklist.md#核对-cicd)
- [记录发布证据](/developer-guide/plugin/release-checklist.md#记录发布证据)
## API 变更日志

### [API 变更日志](/developer-guide/plugin/api-changelog.md)

- [2.26.0](/developer-guide/plugin/api-changelog.md#2260)
- [2.25.0](/developer-guide/plugin/api-changelog.md#2250)
- [2.23.0](/developer-guide/plugin/api-changelog.md#2230)
- [2.22.8](/developer-guide/plugin/api-changelog.md#2228)
- [2.22.5](/developer-guide/plugin/api-changelog.md#2225)
- [2.22.1](/developer-guide/plugin/api-changelog.md#2221)
- [2.22.0](/developer-guide/plugin/api-changelog.md#2220)
