package com.github.salilvnair.convengine.builder.api.controller;

import com.github.salilvnair.convengine.builder.api.dto.WorkspaceSnapshot;
import com.github.salilvnair.convengine.builder.api.service.BuilderStudioPersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for Agent Builder Studio persistence operations.
 *
 * Provides save (sync) and load endpoints so the front-end can dual-persist
 * workspace state to both localStorage (Zustand) and the Postgres database.
 *
 *  - POST /api/v1/builder-studio/workspace/{id}/sync  → full upsert
 *  - GET  /api/v1/builder-studio/workspace/{id}       → full load
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/builder-studio")
@RequiredArgsConstructor
public class AgentBuilderStudioController {

    private final BuilderStudioPersistenceService persistenceService;

    /**
     * Saves the entire workspace snapshot to Postgres.
     * The front-end calls this alongside its localStorage persist so both
     * stores stay in sync.
     */
    @PostMapping("/workspace/{workspaceId}/sync")
    public ResponseEntity<Map<String, Object>> syncWorkspace(
            @PathVariable("workspaceId") String workspaceId,
            @RequestBody WorkspaceSnapshot snapshot) {
        try {
            persistenceService.syncWorkspace(workspaceId, snapshot);
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (Exception e) {
            log.error("builder-studio workspace sync failed for {}", workspaceId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    /**
     * Loads the full workspace snapshot from Postgres.
     * Called on app startup so the front-end can hydrate Zustand from the
     * database if localStorage is empty or stale.
     */
    @GetMapping("/workspace/{workspaceId}")
    public ResponseEntity<WorkspaceSnapshot> loadWorkspace(
            @PathVariable("workspaceId") String workspaceId) {
        try {
            WorkspaceSnapshot snapshot = persistenceService.loadWorkspace(workspaceId);
            return ResponseEntity.ok(snapshot);
        } catch (Exception e) {
            log.error("builder-studio workspace load failed for {}", workspaceId, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
