# ADR-012: 统一 Agent 路径配置

- 状态：Accepted
- 日期：2026-08-22

## 背景

旧实现同时使用安装器路径参数、环境变量、目标项目 `.algorithm-debug-agent.json`、Workspace
持久化值和本地 JAR 覆盖。相同路径存在多个来源，用户无法判断实际生效值，也增加了跨机器安装故障。

## 决策

1. `config/agent-settings.json` 是唯一由用户编辑的路径配置文件，所有默认值必须显式存在。
2. 安装器只展开 `%USERPROFILE%` 和 `%LOCALAPPDATA%`，校验所有结果为绝对路径。
3. 安装器生成 OpenCode `lib/installation.mjs`，Tool 从中读取 Workspace 和算法结果目录。
4. 目标算法仓不保存、不读取 Agent 配置文件。
5. `resultJsonDirectory` 支持绝对路径；历史 Workspace 中的安全相对路径继续按模块根目录解析。
6. CLI 路径参数和 Collector 环境变量仅作为 Agent 内部子进程传输，不是用户配置入口。
7. CLI、CodePath Launcher 和 JDWP Collector JAR 固定从 Agent 仓库相对位置发现，不支持本机覆盖。
8. 修改配置后必须重新安装并重启 OpenCode；修改 Workspace 不迁移或删除历史证据。

## 结果

用户在不同电脑上只需拉取 Agent 仓库、按需修改一个配置文件并运行安装器。路径错误由安装器的
绝对路径校验或运行时现有结构化失败暴露。没有捕获 JSON 时，Agent 只报告配置目录中没有捕获到
JSON，不推断目录必然错误，也不扫描其他目录。

## 被取代的决策

ADR-011 中关于目标项目内结果目录配置发现的部分被本 ADR 取代；其通用 Maven/JUnit Adapter
决策继续有效。
