package com.github.salilvnair.convengine.container.interceptor;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.ccf.core.model.ContainerComponentRequest;

public interface ContainerDataRequestInterceptor {
    void intercept(ContainerComponentRequest request, EngineSession session);
}
