package com.kaper.forms;

import java.util.Date;

public class Logger {
    /**
     * Primitive info logger ;-)
     */
    public static void logInfo(String info) {
        System.out.println(new Date() + ": " + info);
    }
}
