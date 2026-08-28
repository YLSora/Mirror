package com.mirror.client;

/** Signals that Oculus could not construct the optional shader pipeline for a mirror capture. */
public final class MirrorPipelineUnavailableException extends RuntimeException {
    public MirrorPipelineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
