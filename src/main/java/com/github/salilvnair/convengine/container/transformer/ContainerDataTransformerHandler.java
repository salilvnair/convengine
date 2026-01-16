package com.github.salilvnair.convengine.container.transformer;

import com.github.salilvnair.ccf.core.model.ContainerComponentResponse;

import java.util.Map;

public interface ContainerDataTransformerHandler {
    Map<String, Object> transform(ContainerComponentResponse response);
}