package com.github.salilvnair.convengine.engine.core;

import com.github.salilvnair.ccf.core.model.ContainerComponentRequest;
import com.github.salilvnair.ccf.core.model.PageInfoRequest;

public interface ConversationalEngineContainerInterceptor {
    default PageInfoRequest intercept(PageInfoRequest pageInfoRequest) {
        return pageInfoRequest;
    }
    default ContainerComponentRequest intercept(ContainerComponentRequest containerComponentRequest) {
        return containerComponentRequest;
    }
}
