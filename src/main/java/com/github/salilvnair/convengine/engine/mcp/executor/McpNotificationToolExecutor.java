package com.github.salilvnair.convengine.engine.mcp.executor;

import com.github.salilvnair.convengine.engine.mcp.executor.adapter.NotificationExecutorAdapter;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class McpNotificationToolExecutor implements McpToolExecutor {

    private final Optional<NotificationExecutorAdapter> adapter;

    @Override
    public String toolGroup() {
        return "NOTIFICATION";
    }

    @Override
    public String execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return adapter.map(value -> value.execute(tool, args, session))
                .orElseThrow(() -> new IllegalStateException("No NotificationExecutorAdapter configured"));
    }
}

