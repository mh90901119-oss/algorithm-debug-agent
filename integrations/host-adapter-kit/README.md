# Host Adapter Kit

`render-host-config.mjs` 把 Canonical Agent Definition 映射为一个宿主的配置文件。它负责校验规范资产哈希、
从能力清单映射工具权限、渲染宿主模板、附加可选知识，以及通过 staging 和原子目录切换写入结果。

Adapter manifest 和模板不得包含 Tool Schema、证据规则、Case 路径推导、Collector 逻辑或模型凭据。工具清单来自
`capability-manifest-v1.json`；Agent Definition 不再复制工具权限。Java Catalog 与该清单的一致性由构建测试强制
保证。Prompt 原样嵌入并带 SHA-256。只有 Tools 是必需宿主能力；Resources 和 Prompts 是可选增强。

知识加载是确定性的：只接受非符号链接目录中的 UTF-8 `.md` 文件，递归深度、文件数、单文件字节和总字节预算
来自 Agent Definition。文件按相对路径排序后追加到生成的子 Agent Prompt，每段带相对路径、SHA-256 和字节数。
返回给安装器的元数据不包含知识正文或绝对路径。状态含义如下：

- `NOT_CONFIGURED`：没有配置知识目录。
- `ABSENT_OPTIONAL`：配置目录不存在，因知识可选而继续生成。
- `AVAILABLE_EMPTY`：目录存在但没有 Markdown。
- `INCLUDED`：已在预算内注入知识。

无效 UTF-8、符号链接、非普通文件或预算超限会在原子切换前失败，已安装配置保持不变。知识是
`KNOWLEDGE_HINT`，不能成为 Evidence、满足 Predicate 或绕过 Conclusion Gate。

命令行示例：

```powershell
node integrations/host-adapter-kit/scripts/render-host-config.mjs `
  --repository . `
  --adapter integrations/qwen-cli/adapter-manifest.json `
  --output target/rendered-qwen-extension
```
