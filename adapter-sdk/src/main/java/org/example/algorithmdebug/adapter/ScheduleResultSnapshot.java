package org.example.algorithmdebug.adapter;

/** Adapter 从算法结果文件解析出的类型化快照。 */
public interface ScheduleResultSnapshot {

    /**
     * 返回该结果快照自身的 Schema 版本。
     *
     * @return 非空版本字符串
     */
    String schemaVersion();
}

