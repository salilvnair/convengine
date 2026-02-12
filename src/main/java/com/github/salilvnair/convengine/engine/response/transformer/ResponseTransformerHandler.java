package com.github.salilvnair.convengine.engine.response.transformer;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.model.OutputPayload;
import java.util.Map;

public interface ResponseTransformerHandler {
    OutputPayload transform(OutputPayload responsePayload,
                                  EngineSession session,
                                  Map<String, Object> inputParams);
}