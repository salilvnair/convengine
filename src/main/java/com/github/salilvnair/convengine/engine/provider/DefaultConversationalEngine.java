package com.github.salilvnair.convengine.engine.provider;

import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.core.ConversationalEngine;
import com.github.salilvnair.convengine.engine.factory.EnginePipelineFactory;
import com.github.salilvnair.convengine.engine.factory.EngineSessionFactory;
import com.github.salilvnair.convengine.engine.model.EngineResult;
import com.github.salilvnair.convengine.engine.pipeline.EnginePipeline;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class DefaultConversationalEngine implements ConversationalEngine {

    private final EngineSessionFactory sessionFactory;
    private final EnginePipelineFactory pipelineFactory;

    @Override
    public EngineResult process(EngineContext engineContext) {
        EngineSession session = sessionFactory.open(engineContext);
        EnginePipeline pipeline = pipelineFactory.create();
        return pipeline.execute(session);
    }
}
