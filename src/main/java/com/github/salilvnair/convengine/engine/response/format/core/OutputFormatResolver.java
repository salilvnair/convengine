package com.github.salilvnair.convengine.engine.response.format.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;

public interface OutputFormatResolver {

    String format();

    void resolve(
            EngineSession session,
            ResponseTemplate response,
            PromptTemplate template
    );
}
