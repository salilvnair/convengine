package com.github.salilvnair.convengine.engine.response.format.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;

public interface OutputFormatResolver {

    String format();

    void resolve(
            EngineSession session,
            CeResponse response,
            CePromptTemplate template
    );
}
