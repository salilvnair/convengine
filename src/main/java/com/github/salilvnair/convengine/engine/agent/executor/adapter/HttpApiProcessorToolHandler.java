package com.github.salilvnair.convengine.engine.agent.executor.adapter;

import com.github.salilvnair.api.processor.rest.handler.RestWebServiceHandler;
import com.github.salilvnair.convengine.engine.agent.executor.http.ApiProcessorInvocationContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP_API SPI for consumers who want api-processor flow:
 * RestWebServiceFacade -> delegate.invoke -> handler.processResponse.
 */
public interface HttpApiProcessorToolHandler extends HttpApiToolHandler {

    RestWebServiceHandler wsHandler(CeAgentTool tool, Map<String, Object> args, EngineSession session);

    default Map<String, Object> wsMap(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        return new LinkedHashMap<>();
    }

    default ApiProcessorInvocationContext wsContext(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        return new ApiProcessorInvocationContext(args == null ? Map.of() : args);
    }

    default Object[] wsInvocationArgs(
            CeAgentTool tool,
            Map<String, Object> args,
            EngineSession session,
            ApiProcessorInvocationContext context
    ) {
        return new Object[]{context};
    }

    default Class<?> responseMapperClass(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        return null;
    }

    default Object extractResponse(
            CeAgentTool tool,
            Map<String, Object> args,
            EngineSession session,
            Map<String, Object> wsMap,
            ApiProcessorInvocationContext context
    ) {
        if (context != null) {
            if (context.getMappedResponse() != null) {
                return context.getMappedResponse();
            }
            if (context.getRawResponse() != null) {
                return context.getRawResponse();
            }
        }
        if (wsMap == null || wsMap.isEmpty()) {
            return Map.of();
        }
        if (wsMap.containsKey("mappedResponse")) {
            return wsMap.get("mappedResponse");
        }
        if (wsMap.containsKey("response")) {
            return wsMap.get("response");
        }
        return wsMap;
    }

    @Override
    default Object execute(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        return wsContext(tool, args, session);
    }
}
