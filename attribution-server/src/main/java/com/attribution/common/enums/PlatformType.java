package com.attribution.common.enums;

public enum PlatformType {
    APK("apk", "Android应用"),
    HAP("hap", "鸿蒙应用"),
    RPK("rpk", "快游戏");

    private final String code;
    private final String desc;

    PlatformType(String code, String desc) { this.code = code; this.desc = desc; }

    public String getCode() { return code; }
    public String getDesc() { return desc; }

    public static PlatformType fromCode(String code) {
        for (PlatformType t : values()) {
            if (t.code.equalsIgnoreCase(code)) return t;
        }
        return null;
    }
}
