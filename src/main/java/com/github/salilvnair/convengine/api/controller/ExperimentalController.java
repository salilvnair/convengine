package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationRequest;
import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationResponse;
import com.github.salilvnair.convengine.experimental.ExperimentalSqlGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversation/experimental")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "convengine.experimental", name = "enabled", havingValue = "true")
public class ExperimentalController {

    private final ExperimentalSqlGenerationService service;

    @PostMapping("/generate-sql")
    public ExperimentalSqlGenerationResponse generateSql(@RequestBody ExperimentalSqlGenerationRequest request) {
        return service.generate(request);
    }
}
