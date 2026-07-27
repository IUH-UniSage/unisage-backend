package com.unisage.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class AiAgentClientException extends RuntimeException {

    private final HttpStatusCode upstreamStatus;
    private final String responseBody;

    public AiAgentClientException(HttpStatusCode upstreamStatus, String responseBody) {
        super("AI agent rejected the request with status " + upstreamStatus.value());
        this.upstreamStatus = upstreamStatus;
        this.responseBody = responseBody;
    }
}
