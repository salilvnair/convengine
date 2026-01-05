package com.github.salilvnair.convengine.engine.core;

import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.model.EngineResult;

public interface ConversationalEngine {
    EngineResult process(EngineContext engineContext);
}
