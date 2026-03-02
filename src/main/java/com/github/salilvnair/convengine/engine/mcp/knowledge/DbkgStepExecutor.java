package com.github.salilvnair.convengine.engine.mcp.knowledge;

import java.util.Map;

public interface DbkgStepExecutor {
    boolean supports(String executorCode);

    Map<String, Object> execute(String stepCode, String templateCode, Map<String, Object> config, Map<String, Object> runtime);
}
