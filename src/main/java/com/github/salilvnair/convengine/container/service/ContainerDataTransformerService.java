package com.github.salilvnair.convengine.container.service;

import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;
import com.github.salilvnair.convengine.container.annotation.ContainerDataTransformer;
import com.github.salilvnair.convengine.container.transformer.ContainerDataTransformerHandler;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ContainerDataTransformerService {

    private final ApplicationContext ctx;

    private final Map<Key, ContainerDataTransformerHandler> registry =
            new HashMap<>();

    @PostConstruct
    void init() {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(ContainerDataTransformer.class);

        for (Object bean : beans.values()) {
            ContainerDataTransformer ann = bean.getClass().getAnnotation(ContainerDataTransformer.class);

            if (!(bean instanceof ContainerDataTransformerHandler handler)) {
                throw new ConversationEngineException(
                        ConversationEngineErrorCode.INVALID_CONTAINER_TRANSFORMER,
                        bean.getClass().getName() + " must implement ContainerDataTransformerHandler"
                );

            }

            registry.put(
                    new Key(ann.intent(), ann.state()),
                    handler
            );
        }
    }

    public Map<String, Object> transformIfApplicable(
            ContainerComponentResponse response,
            EngineSession session,
            Map<String, Object> inputParams
    ) {
        ContainerDataTransformerHandler handler =
                registry.get(new Key(session.getIntent(), session.getState()));

        if (handler == null) {
            return null;
        }

        return handler.transform(response, session, inputParams);
    }

    // ---- key ----
    record Key(String intent, String state) {}
}
