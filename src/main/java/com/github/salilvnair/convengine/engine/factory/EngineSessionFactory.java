package com.github.salilvnair.convengine.engine.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class EngineSessionFactory {

    private final ObjectMapper mapper;

    public EngineSession open(EngineContext ctx) {
        return new EngineSession(ctx, mapper);
    }
}
