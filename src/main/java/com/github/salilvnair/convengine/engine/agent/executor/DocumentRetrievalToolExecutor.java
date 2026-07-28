package com.github.salilvnair.convengine.engine.agent.executor;

import com.github.salilvnair.convengine.engine.agent.executor.adapter.DocumentRetrievalExecutorAdapter;
import com.github.salilvnair.convengine.engine.agent.AgentConstants;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeAgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DocumentRetrievalToolExecutor implements AgentToolExecutor {

    private final Optional<DocumentRetrievalExecutorAdapter> adapter;

    @Override
    public String toolGroup() {
        return AgentConstants.TOOL_GROUP_DOCUMENT_RETRIEVAL;
    }

    @Override
    public String execute(CeAgentTool tool, Map<String, Object> args, EngineSession session) {
        return adapter.map(value -> value.execute(tool, args, session))
                .orElseThrow(() -> new IllegalStateException("No DocumentRetrievalExecutorAdapter configured"));
    }
}
