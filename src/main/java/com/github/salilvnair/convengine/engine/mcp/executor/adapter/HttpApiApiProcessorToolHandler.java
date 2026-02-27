package com.github.salilvnair.convengine.engine.mcp.executor.adapter;

import com.github.salilvnair.api.processor.rest.handler.RestWebServiceHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.http.ApiProcessorInvocationContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP_API SPI for consumers who want api-processor flow:
 * RestWebServiceFacade -> delegate.invoke -> handler.processResponse.
 */
public interface HttpApiApiProcessorToolHandler extends HttpApiToolHandler {

    RestWebServiceHandler wsHandler(CeMcpTool tool, Map<String, Object> args, EngineSession session);

    default Map<String, Object> wsMap(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return new LinkedHashMap<>();
    }

    default ApiProcessorInvocationContext wsContext(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return new ApiProcessorInvocationContext(args == null ? Map.of() : args);
    }

    default Object[] wsInvocationArgs(
            CeMcpTool tool,
            Map<String, Object> args,
            EngineSession session,
            ApiProcessorInvocationContext context
    ) {
        return new Object[]{context};
    }

    default Class<?> responseMapperClass(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return null;
    }

    default Object extractResponse(
            CeMcpTool tool,
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
    default Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return wsContext(tool, args, session);
    }
}
