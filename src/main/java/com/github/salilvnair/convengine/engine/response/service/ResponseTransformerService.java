package com.github.salilvnair.convengine.engine.response.service;

import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.response.annotation.ResponseTransformer;
import com.github.salilvnair.convengine.engine.response.transformer.ResponseTransformerHandler;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.model.OutputPayload;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResponseTransformerService {

    private final ApplicationContext ctx;

    private final Map<Key, ResponseTransformerHandler> registry = new HashMap<>();

    @PostConstruct
    void init() {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(ResponseTransformer.class);

        for (Object bean : beans.values()) {
            ResponseTransformer ann = bean.getClass().getAnnotation(ResponseTransformer.class);

            if (!(bean instanceof ResponseTransformerHandler handler)) {
                throw new ConversationEngineException(
                        ConversationEngineErrorCode.INVALID_RESPONSE_TRANSFORMER,
                        bean.getClass().getName() + " must implement ContainerDataTransformerHandler"
                );

            }
            registry.put(
                    new Key(ann.intent(), ann.state()),
                    handler
            );
        }
    }

    public OutputPayload transformIfApplicable(
            OutputPayload responsePayload,
            EngineSession session,
            Map<String, Object> inputParams
    ) {
        ResponseTransformerHandler handler =
                registry.get(new Key(session.getIntent(), session.getState()));

        if (handler == null) {
            return responsePayload;
        }

        OutputPayload transformed = handler.transform(responsePayload, session, inputParams);
        if (transformed == null) {
            return responsePayload;
        }
        return transformed;
    }

    // ---- key ----
    record Key(String intent, String state) {}
}
