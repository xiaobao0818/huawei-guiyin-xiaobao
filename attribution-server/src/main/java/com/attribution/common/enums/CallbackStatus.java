package com.attribution.common.enums;

public enum CallbackStatus {
    PENDING("pending", "待回传"),
    SUCCESS("success", "回传成功"),
    FAILED("failed", "回传失败");

    private final String code;
    private final String desc;

    CallbackStatus(String code, String desc) { this.code = code; this.desc = desc; }

    public String getCode() { return code; }
    public String getDesc() { return desc; }
}
