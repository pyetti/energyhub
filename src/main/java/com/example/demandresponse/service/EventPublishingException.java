package com.example.demandresponse.service;

/**
 * Raised when an event passes validation but cannot be published to SNS.
 * <br/>
 * Distinct from {@link IllegalArgumentException}, which means the caller sent a bad request:
 * this one means we failed, so it surfaces to the caller as a server-side error.
 */
public class EventPublishingException extends RuntimeException {

    public EventPublishingException(String message) {
        super(message);
    }

    public EventPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}
