package com.kaper.forms;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Primitive logger ;-)
 */
public class Logger {
    private static boolean enableDebugLogging = false;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    public static boolean isDebugEnabled() {
        return enableDebugLogging;
    }

    public static void setDebugEnabled(boolean enabled) {
        enableDebugLogging = enabled;
    }

    public static void logDebug(String info) {
        if (enableDebugLogging) {
            log("DEBUG", info);
        }
    }

    public static void logInfo(String info) {
        log("INFO", info);
    }

    private static void log(String level, String info) {
        System.out.println(DATE_FORMAT.format(new Date()) + " [" + Thread.currentThread().getName() + "] " + level + ": " + info);
    }
}
