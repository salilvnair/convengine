package com.github.salilvnair.convengine.engine.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class EngineContext {
    private String conversationId;
    private String userText;
    private Map<String, Object> inputParams;
}
