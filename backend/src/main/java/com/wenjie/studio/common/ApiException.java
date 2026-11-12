package com.wenjie.studio.common;

public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Long currentRevision;

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, null);
    }

    public ApiException(int status, String code, String detail, Long currentRevision) {
        super(detail);
        this.status = status;
        this.code = code;
        this.currentRevision = currentRevision;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Long currentRevision() { return currentRevision; }
}
