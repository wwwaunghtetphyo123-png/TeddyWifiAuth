package com.violet.wifidogauthenticator.models;

public class LogModel {

    public static final int TYPE_INFO    = 0;
    public static final int TYPE_SUCCESS = 1;
    public static final int TYPE_ERROR   = 2;
    public static final int TYPE_WARN    = 3;

    private final String timestamp;
    private final String message;
    private final int    type;

    public LogModel(String timestamp, String message, int type) {
        this.timestamp = timestamp;
        this.message   = message;
        this.type      = type;
    }

    public String getTimestamp() { return timestamp; }
    public String getMessage()   { return message; }
    public int    getType()      { return type; }
}
