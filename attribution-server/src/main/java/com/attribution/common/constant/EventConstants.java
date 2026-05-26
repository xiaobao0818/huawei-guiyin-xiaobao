package com.attribution.common.constant;

/**
 * Well-known event names used throughout the system.
 * Centralizing these avoids hard-coded strings scattered across services and repositories.
 */
public final class EventConstants {

    private EventConstants() {}

    public static final String ACTIVATE = "activate";
    public static final String REGISTER = "register";
    public static final String LOGIN = "login";
    public static final String PURCHASE = "purchase";
    public static final String RETAIN_1D = "retain_1d";
    public static final String RETAIN_7D = "retain_7d";
    public static final String LEVEL_UP = "level_up";
    public static final String LEVEL_COMPLETE = "level_complete";
    public static final String TUTORIAL_COMPLETE = "tutorial_complete";
    public static final String CUSTOM = "custom";
}
