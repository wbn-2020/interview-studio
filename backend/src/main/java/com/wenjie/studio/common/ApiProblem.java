package com.wenjie.studio.common;

public record ApiProblem(String type, String title, int status, String detail,
                         String instance, String code, String traceId,
                         boolean retryable, Long currentRevision) {
}
