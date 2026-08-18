# Method Path SPI

该模块定义 CodePath 采集器与 Core 之间的稳定边界：一次请求、一个 v2 Manifest、一个原始 JSONL 流和两份有界日志。
Manifest 保存身份、工具与 Plan Hash、进程事实、独立的目标 UT 结果与 JUnit 计数、事件/字节计数、截断原因和失败诊断；
因此工具失败与 UT 失败同时出现时，两组事实都不会丢失。它不包含包范围、二次过滤文件或源码指纹。

```powershell
mvn -pl method-path-spi -am test
```
