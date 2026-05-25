package com.attribution.common.enums;

public enum AttributionType {
    OAID("oaid", "OAID精确匹配"),
    FINGERPRINT("fingerprint", "设备指纹匹配"),
    CHANNEL("channel", "渠道号匹配");

    private final String code;
    private final String desc;

    AttributionType(String code, String desc) { this.code = code; this.desc = desc; }

    public String getCode() { return code; }
    public String getDesc() { return desc; }
}
