package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditStreamEventResponse {
    private Long auditId;
    private String stage;
    private String createdAt;
    private Map<String, Object> payload;
}
