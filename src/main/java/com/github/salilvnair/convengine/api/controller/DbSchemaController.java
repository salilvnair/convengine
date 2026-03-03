package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.DbSchemaAgentGenerateRequest;
import com.github.salilvnair.convengine.api.dto.DbSchemaAgentGenerateResponse;
import com.github.salilvnair.convengine.cache.DbSchemaAgentService;
import com.github.salilvnair.convengine.cache.DbSchemaInspectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/db")
public class DbSchemaController {

    private final DbSchemaInspectorService dbSchemaInspectorService;
    private final DbSchemaAgentService dbSchemaAgentService;

    @GetMapping("/inspect-schema")
    public ResponseEntity<Map<String, Object>> inspectSchema(
            @RequestParam(name = "prefix", required = false) String prefix,
            @RequestParam(name = "schema", required = false) String schema
    ) {
        return ResponseEntity.ok(dbSchemaInspectorService.inspect(schema, prefix));
    }

    @PostMapping("/agent")
    public ResponseEntity<DbSchemaAgentGenerateResponse> generateSchemaSeed(
            @RequestBody DbSchemaAgentGenerateRequest request
    ) {
        return ResponseEntity.ok(dbSchemaAgentService.generateSeedSql(request));
    }
}
