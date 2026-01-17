package com.github.salilvnair.convengine.engine.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PendingClarification {
    private String question;          // what assistant asked
    private String reason;            // why clarification needed
    private List<String> options;     // optional
}
