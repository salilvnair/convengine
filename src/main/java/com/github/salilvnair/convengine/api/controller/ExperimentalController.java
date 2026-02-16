package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationRequest;
import com.github.salilvnair.convengine.api.dto.ExperimentalSqlGenerationResponse;
import com.github.salilvnair.convengine.experimental.ExperimentalSqlGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

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

    @PostMapping(value = "/generate-sql/zip", produces = "application/zip")
    public ResponseEntity<byte[]> generateSqlZip(@RequestBody ExperimentalSqlGenerationRequest request) {
        ExperimentalSqlGenerationResponse response = service.generate(request);
        byte[] zip = service.buildSqlZip(response);
        String ts = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "convengine_seed_" + ts + ".zip";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zip.length)
                .body(zip);
    }
}
