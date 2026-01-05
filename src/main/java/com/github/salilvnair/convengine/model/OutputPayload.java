package com.github.salilvnair.convengine.model;

public sealed interface OutputPayload
        permits TextPayload, JsonPayload {}
