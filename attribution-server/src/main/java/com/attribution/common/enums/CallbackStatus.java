package com.attribution.common.enums;

import java.util.Set;

public enum CallbackStatus {
    /** 匹配成功，回传任务已入队 */
    PENDING("pending", "待回传"),
    /** Worker 正在处理中 (仅 callback_task) */
    SENDING("sending", "处理中"),
    /** 回传失败，等待重试 (仅 callback_task) */
    RETRY_PENDING("retry_pending", "等待重试"),
    /** 回传成功 */
    SUCCESS("success", "回传成功"),
    /** 回传失败 (attribution_record 最终状态) */
    FAILED("failed", "回传失败"),
    /** 重试耗尽 (callback_task 最终状态) */
    DEAD("dead", "永久失败"),
    /** 归因匹配失败，无点击记录 */
    UNMATCHED("unmatched", "未匹配"),
    /** 事件不需要回传 */
    NO_CALLBACK("no_callback", "无需回传");

    private final String code;
    private final String desc;

    /** callback_task statuses eligible for the worker to pick up. */
    public static final Set<String> DUE_STATUSES = Set.of(PENDING.code, RETRY_PENDING.code);

    /** callback_task terminal statuses (safe to clean up). */
    public static final Set<String> TERMINAL_STATUSES = Set.of(SUCCESS.code, DEAD.code);

    /** attribution_record statuses that represent an attempted callback. */
    public static final Set<String> ATTEMPT_STATUSES = Set.of(PENDING.code, SUCCESS.code, FAILED.code);

    CallbackStatus(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() { return code; }
    public String getDesc() { return desc; }
}
