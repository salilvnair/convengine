package com.github.salilvnair.convengine.engine.mcp.executor.http;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class ApiProcessorInvocationContext {

    private final Map<String, Object> inputArgs;
    private Object rawResponse;
    private Object mappedResponse;

    public ApiProcessorInvocationContext(Map<String, Object> inputArgs) {
        this.inputArgs = inputArgs;
    }
}
