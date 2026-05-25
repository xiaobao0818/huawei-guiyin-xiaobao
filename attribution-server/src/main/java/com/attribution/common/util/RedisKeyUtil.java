package com.attribution.common.util;

public class RedisKeyUtil {

    private static final String CLICK_CACHE_PREFIX = "attribution:click";
    private static final String ACTIVE_LOCK_PREFIX = "attribution:active:lock";

    public static String clickCacheKey(String gameId, String oaid) {
        return CLICK_CACHE_PREFIX + ":" + gameId + ":" + oaid;
    }

    public static String activeLockKey(String gameId, String oaid) {
        return ACTIVE_LOCK_PREFIX + ":" + gameId + ":" + oaid;
    }
}
