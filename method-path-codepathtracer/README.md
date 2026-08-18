# CodePathTracer Adapter

该适配器启动受控 JUnit Launcher，并把已归档的 CodePath Plan v2 传给 Launcher。Launcher 在事件写出前按
`className + methodName + descriptor` 精确匹配，只产生一个 `raw/codepath.jsonl`；适配器负责超时、日志、
退出码、Manifest 和失败清理，不再先采集包级数据再二次过滤。

当前目标场景是单线程 UT。第二个命中所选方法的线程会产生结构化不支持原因；事件数、Raw 字节数和总耗时均有硬预算。
命中预算后 Launcher 停止后续事件生成、JSON 格式化和 Sink 调用，但继续运行目标 UT，以保留真实测试结果。
Manifest 独立保存目标 UT 状态与 JUnit 计数，因此多线程工具错误不会遮蔽同时发生的断言或算法异常。
由于本阶段不修改上游 CodePathTracer，未选方法仍可能产生上游 Advice 回调成本，但不会被格式化或写入 Raw；在真实大型算法上
必须通过 smoke/性能测量确认该开销，而不能宣称已消除。

```powershell
mvn -Pcodepath-launcher -pl method-path-codepathtracer -am test
```
