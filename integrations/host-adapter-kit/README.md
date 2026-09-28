# Host Adapter Kit

`render-host-config.mjs` 把 Canonical Agent Definition 映射为一个宿主的配置文件。它只做四件事：校验规范资产
哈希、映射工具权限、渲染宿主模板、以 staging 加原子目录切换写入结果。

Adapter manifest 和模板不得包含 Tool Schema、证据规则、Case 路径推导、Collector 逻辑或模型凭据。工具清单来自
Agent Definition 与 capability manifest 的一致交集；Prompt 原样嵌入并带 SHA-256。知识目录不存在时返回
`ABSENT_OPTIONAL`，不会阻断配置生成。

命令行示例：

```powershell
node integrations/host-adapter-kit/scripts/render-host-config.mjs `
  --repository . `
  --adapter integrations/qwen-cli/adapter-manifest.json `
  --output target/rendered-qwen-extension
```
