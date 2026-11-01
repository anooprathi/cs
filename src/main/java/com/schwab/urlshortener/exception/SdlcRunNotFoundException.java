package com.schwab.urlshortener.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an agentic SDLC run (or one of its stage artifacts) does not exist. */
public class SdlcRunNotFoundException extends ApiException {

    public SdlcRunNotFoundException(Long runId) {
        super(HttpStatus.NOT_FOUND, "No SDLC run found with id: " + runId);
    }

    public SdlcRunNotFoundException(Long runId, String stage) {
        super(HttpStatus.NOT_FOUND, "No " + stage + " artifact found for SDLC run: " + runId);
    }
}
