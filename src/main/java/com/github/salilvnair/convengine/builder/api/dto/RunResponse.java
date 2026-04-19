package com.github.salilvnair.convengine.builder.api.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Response envelope for the full-graph run endpoint. Mirrors what the UI's
 * RunModal expects: a final output (as a string), an ordered trace of every
 * node that was executed, and an optional top-level error message.
 */
@Data
public class RunResponse {
    private String output;
    private List<TraceEntry> trace = new ArrayList<>();
    private String error;

    @Data
    public static class TraceEntry {
        private String nodeId;
        private String blockType;
        private String title;
        private String input;
        private String output;
        private String error;
        private Long ms;
    }
}
