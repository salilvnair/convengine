package com.github.salilvnair.convengine.engine.exception;

import lombok.Getter;

@Getter
public class ConversationEngineException extends RuntimeException {

    private final String errorCode;
    private final boolean recoverable;

    public ConversationEngineException(
            ConversationEngineErrorCode code
    ) {
        super(code.defaultMessage());
        this.errorCode = code.name();
        this.recoverable = code.recoverable();
    }

    public ConversationEngineException(
            ConversationEngineErrorCode code,
            String overrideMessage
    ) {
        super(overrideMessage);
        this.errorCode = code.name();
        this.recoverable = code.recoverable();
    }

    public ConversationEngineException(
            ConversationEngineErrorCode code,
            String overrideMessage,
            boolean recoverable
    ) {
        super(overrideMessage);
        this.errorCode = code.name();
        this.recoverable = recoverable;
    }

}
