package com.github.salilvnair.convengine.engine.model;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StepTiming {
    private String stepName;
    private long startedAtNs;
    private long endedAtNs;
    private long durationMs;

    private boolean success;
    private String error;
}
