package com.github.salilvnair.convengine.container.interceptor;

import com.github.salilvnair.convengine.container.annotation.ContainerDataInterceptor;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.ccf.core.model.ContainerComponentRequest;
import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContainerDataInterceptorExecutor {

    private final ContainerDataInterceptorRegistry registry;

    public void beforeExecute(
            ContainerComponentRequest req,
            EngineSession session
    ) {
        for (ContainerDataRequestInterceptor interceptor : registry.requestInterceptors()) {
            if (!matches(interceptor, session)) continue;
            interceptor.intercept(req, session);
        }
    }

    public ContainerComponentResponse afterExecute(
            ContainerComponentResponse resp,
            EngineSession session
    ) {
        ContainerComponentResponse current = resp;

        for (ContainerDataResponseInterceptor interceptor : registry.responseInterceptors()) {
            if (!matches(interceptor, session)) continue;
            current = interceptor.intercept(current, session);
        }

        return current;
    }

    private boolean matches(Object interceptor, EngineSession session) {
        ContainerDataInterceptor ann = interceptor.getClass().getAnnotation(ContainerDataInterceptor.class);

        boolean intentMatch =
                "*".equals(ann.intent()) ||
                        ann.intent().equalsIgnoreCase(session.getIntent());

        boolean stateMatch =
                "*".equals(ann.state()) ||
                        ann.state().equalsIgnoreCase(session.getState());

        return intentMatch && stateMatch;
    }
}
