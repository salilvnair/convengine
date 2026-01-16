package com.github.salilvnair.convengine.container.interceptor;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;

public interface ContainerDataResponseInterceptor {
    ContainerComponentResponse intercept(ContainerComponentResponse response, EngineSession session);
}
