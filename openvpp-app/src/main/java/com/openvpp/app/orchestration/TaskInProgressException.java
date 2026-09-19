package com.openvpp.app.orchestration;

/**
 * 同键任务认领竞争超限（有限次重试后仍无法原子认领或接管残留任务）。
 * 控制器映射为 409：任务正在处理中，调用方稍后重试即可读到最终结果。
 * 正常执行时长远小于认领等待窗口，仅 pathological 场景（数据库锁等待超时）触发。
 */
public class TaskInProgressException extends RuntimeException {

    public TaskInProgressException(String responseId) {
        super("任务正在处理中，请稍后重试: " + responseId);
    }
}
