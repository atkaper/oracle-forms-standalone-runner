package com.kaper.forms;

public class ProxyOverride {
    public final String pathRegex;
    public final String localFile;
    public final String contentType;

    public ProxyOverride(final String pathRegex, final String localFile, final String contentType) {
        this.pathRegex = pathRegex;
        this.localFile = localFile;
        this.contentType = contentType;
    }
}
