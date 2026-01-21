package com.github.salilvnair.convengine.container.transformer;

import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;
import com.github.salilvnair.convengine.engine.session.EngineSession;

import java.util.Map;

public interface ContainerDataTransformerHandler {
    Map<String, Object> transform(ContainerComponentResponse response,
                                  EngineSession session,
                                  Map<String, Object> inputParams);
}