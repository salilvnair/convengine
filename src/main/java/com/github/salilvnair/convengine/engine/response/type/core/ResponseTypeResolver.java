package com.github.salilvnair.convengine.engine.response.type.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;

public interface ResponseTypeResolver {

    String type();

    void resolve(EngineSession session, PromptTemplate template, ResponseTemplate response);
}
