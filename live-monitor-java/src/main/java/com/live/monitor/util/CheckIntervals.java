package com.live.monitor.util;

import java.util.Locale;

public final class CheckIntervals {
    public static final int DEFAULT_SECONDS = 60;
    public static final int MIN_SECONDS = 1;
    public static final int MAX_SECONDS = 31_536_000;

    private CheckIntervals() {
    }

    public static int fromValueAndUnit(Integer value, String unit, Integer fallbackSeconds) {
        if (value == null) {
            return normalizeSeconds(fallbackSeconds);
        }
        if (value < 1) {
            throw new IllegalArgumentException("check interval must be greater than 0");
        }
        long seconds = (long) value * multiplier(unit);
        if (seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("check interval cannot exceed 365 days");
        }
        return (int) seconds;
    }

    public static int normalizeSeconds(Integer seconds) {
        int value = seconds == null ? DEFAULT_SECONDS : seconds;
        if (value < MIN_SECONDS) {
            throw new IllegalArgumentException("check interval must be greater than 0");
        }
        if (value > MAX_SECONDS) {
            throw new IllegalArgumentException("check interval cannot exceed 365 days");
        }
        return value;
    }

    public static String displayUnit(Integer seconds) {
        int value = safeSeconds(seconds);
        if (value % 86_400 == 0) {
            return "days";
        }
        if (value % 3_600 == 0) {
            return "hours";
        }
        if (value % 60 == 0) {
            return "minutes";
        }
        return "seconds";
    }

    public static int displayValue(Integer seconds) {
        int value = safeSeconds(seconds);
        String unit = displayUnit(value);
        if ("days".equals(unit)) {
            return value / 86_400;
        }
        if ("hours".equals(unit)) {
            return value / 3_600;
        }
        if ("minutes".equals(unit)) {
            return value / 60;
        }
        return value;
    }

    private static int safeSeconds(Integer seconds) {
        try {
            return normalizeSeconds(seconds);
        } catch (IllegalArgumentException ex) {
            return DEFAULT_SECONDS;
        }
    }

    private static int multiplier(String unit) {
        String normalized = unit == null ? "seconds" : unit.trim().toLowerCase(Locale.ROOT);
        if ("second".equals(normalized) || "seconds".equals(normalized) || "sec".equals(normalized)
            || "s".equals(normalized) || "秒".equals(normalized)) {
            return 1;
        }
        if ("minute".equals(normalized) || "minutes".equals(normalized) || "min".equals(normalized)
            || "m".equals(normalized) || "分钟".equals(normalized) || "分".equals(normalized)) {
            return 60;
        }
        if ("hour".equals(normalized) || "hours".equals(normalized) || "hr".equals(normalized)
            || "h".equals(normalized) || "小时".equals(normalized) || "时".equals(normalized)) {
            return 3_600;
        }
        if ("day".equals(normalized) || "days".equals(normalized) || "d".equals(normalized)
            || "天".equals(normalized) || "日".equals(normalized)) {
            return 86_400;
        }
        throw new IllegalArgumentException("unsupported check interval unit");
    }
}
