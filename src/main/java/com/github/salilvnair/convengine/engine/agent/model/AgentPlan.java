package com.github.salilvnair.convengine.engine.agent.model;

import java.util.Map;

public record AgentPlan(
        String action,          // CALL_TOOL | ANSWER
        String tool_code,
        Map<String, Object> args,
        String answer,
        String operation_tag
) {}
