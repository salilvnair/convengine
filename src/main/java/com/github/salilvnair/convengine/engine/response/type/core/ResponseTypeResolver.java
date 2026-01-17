package com.github.salilvnair.convengine.engine.response.type.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeResponse;

public interface ResponseTypeResolver {

    String type();

    void resolve(EngineSession session, CeResponse response);
}
