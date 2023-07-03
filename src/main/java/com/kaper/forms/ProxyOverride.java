package com.kaper.forms;

public class ProxyOverride {
    public final String requestRegex;
    public final String localFile;
    public final String contentType;

    public ProxyOverride(final String requestRegex, final String localFile, final String contentType) {
        this.requestRegex = requestRegex;
        this.localFile = localFile;
        this.contentType = contentType;
    }
}
