package com.github.salilvnair.convengine.builder.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.builder.api.dto.WorkspaceSnapshot;
import com.github.salilvnair.convengine.builder.api.dto.WorkspaceSnapshot.*;
import com.github.salilvnair.convengine.builder.entity.*;
import com.github.salilvnair.convengine.builder.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistence service for the Agent Builder Studio workspace.
 *
 * Converts between the front-end Zustand snapshot shape
 * ({@link WorkspaceSnapshot}) and the relational JPA entities, handling
 * save (upsert-all) and load operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuilderStudioPersistenceService {

    private final BsWorkspaceRepository workspaceRepo;
    private final BsTeamRepository teamRepo;
    private final BsAgentPoolRepository agentPoolRepo;
    private final BsAgentRepository agentRepo;
    private final BsAgentSkillRepository agentSkillRepo;
    private final BsSkillRepository skillRepo;
    private final BsWorkflowRepository workflowRepo;
    private final BsLlmConfigRepository llmConfigRepo;
    private final ObjectMapper mapper;

    // ───────────────────────────────────────────── SAVE (full sync) ─────

    /**
     * Upserts the entire workspace snapshot into the database.
     * Deletes entities that exist in DB but are absent from the snapshot
     * (i.e. the front-end deleted them).
     */
    @Transactional
    public void syncWorkspace(String workspaceId, WorkspaceSnapshot snapshot) {
        // 1. Workspace
        for (WorkspaceDto ws : safe(snapshot.getWorkspaces())) {
            workspaceRepo.save(CeBsWorkspace.builder()
                    .workspaceId(ws.getId())
                    .name(ws.getName())
                    .description(ws.getDescription())
                    .build());
        }

        // 2. Teams
        Set<String> incomingTeamIds = new HashSet<>();
        for (TeamDto t : safe(snapshot.getTeams())) {
            incomingTeamIds.add(t.getId());
            teamRepo.save(CeBsTeam.builder()
                    .teamId(t.getId())
                    .workspaceId(t.getWorkspaceId() != null ? t.getWorkspaceId() : workspaceId)
                    .name(t.getName())
                    .build());
        }
        // Remove teams deleted on the front-end
        teamRepo.findByWorkspaceId(workspaceId).stream()
                .filter(e -> !incomingTeamIds.contains(e.getTeamId()))
                .forEach(teamRepo::delete);

        // 3. Agent Pools
        Set<String> incomingPoolIds = new HashSet<>();
        for (AgentPoolDto p : safe(snapshot.getAgentPools())) {
            incomingPoolIds.add(p.getId());
            agentPoolRepo.save(CeBsAgentPool.builder()
                    .poolId(p.getId())
                    .teamId(p.getTeamId())
                    .name(p.getName())
                    .build());
        }
        // Cleanup removed pools within workspace teams
        for (String teamId : incomingTeamIds) {
            agentPoolRepo.findByTeamId(teamId).stream()
                    .filter(e -> !incomingPoolIds.contains(e.getPoolId()))
                    .forEach(agentPoolRepo::delete);
        }

        // 4. Skills
        Set<String> incomingSkillIds = new HashSet<>();
        for (SkillDto s : safe(snapshot.getSkills())) {
            incomingSkillIds.add(s.getId());
            skillRepo.save(CeBsSkill.builder()
                    .skillId(s.getId())
                    .workspaceId(s.getWorkspaceId() != null ? s.getWorkspaceId() : workspaceId)
                    .name(s.getName())
                    .language(s.getLanguage())
                    .source(s.getSource())
                    .inputSchema(toJson(s.getInputSchema()))
                    .outputSchema(toJson(s.getOutputSchema()))
                    .build());
        }
        skillRepo.findByWorkspaceId(workspaceId).stream()
                .filter(e -> !incomingSkillIds.contains(e.getSkillId()))
                .forEach(skillRepo::delete);

        // 5. Agents + agent-skill mappings
        Set<String> incomingAgentIds = new HashSet<>();
        for (AgentDto a : safe(snapshot.getAgents())) {
            incomingAgentIds.add(a.getId());
            agentRepo.save(CeBsAgent.builder()
                    .agentId(a.getId())
                    .poolId(a.getPoolId())
                    .name(a.getName())
                    .model(a.getModel())
                    .provider(a.getProvider())
                    .systemPrompt(a.getSystemPrompt())
                    .userPrompt(a.getUserPrompt())
                    .inputSchema(toJson(a.getInputSchema()))
                    .outputSchema(toJson(a.getOutputSchema()))
                    .strictInput(a.getStrictInput())
                    .strictOutput(a.getStrictOutput())
                    .build());
            // Sync skill attachments
            agentSkillRepo.deleteByAgentId(a.getId());
            for (String skillId : safe(a.getAttachedSkillIds())) {
                agentSkillRepo.save(CeBsAgentSkill.builder()
                        .agentId(a.getId())
                        .skillId(skillId)
                        .build());
            }
        }
        // Cleanup removed agents within workspace pools
        for (String poolId : incomingPoolIds) {
            agentRepo.findByPoolId(poolId).stream()
                    .filter(e -> !incomingAgentIds.contains(e.getAgentId()))
                    .forEach(e -> {
                        agentSkillRepo.deleteByAgentId(e.getAgentId());
                        agentRepo.delete(e);
                    });
        }

        // 6. Workflows
        Set<String> incomingWfIds = new HashSet<>();
        for (WorkflowDto w : safe(snapshot.getWorkflows())) {
            incomingWfIds.add(w.getId());
            workflowRepo.save(CeBsWorkflow.builder()
                    .workflowId(w.getId())
                    .workspaceId(workspaceId)
                    .teamId(w.getTeamId())
                    .name(w.getName())
                    .description(w.getDescription())
                    .nodes(toJson(w.getNodes()))
                    .edges(toJson(w.getEdges()))
                    .subBlockValues(toJson(w.getSubBlockValues()))
                    .metadata(toJson(w.getMetadata()))
                    .build());
        }
        workflowRepo.findByWorkspaceId(workspaceId).stream()
                .filter(e -> !incomingWfIds.contains(e.getWorkflowId()))
                .forEach(workflowRepo::delete);

        // 7. LLM Config
        if (snapshot.getLlmConfig() != null) {
            llmConfigRepo.save(CeBsLlmConfig.builder()
                    .workspaceId(workspaceId)
                    .configJson(toJson(snapshot.getLlmConfig()))
                    .build());
        } else {
            llmConfigRepo.deleteById(workspaceId);
        }
    }

    // ───────────────────────────────────────────── LOAD ─────────────────

    /**
     * Loads the full workspace snapshot from the database, returning a DTO
     * that the front-end can merge into Zustand state directly.
     */
    @Transactional(readOnly = true)
    public WorkspaceSnapshot loadWorkspace(String workspaceId) {
        WorkspaceSnapshot snap = new WorkspaceSnapshot();

        // Workspace
        workspaceRepo.findById(workspaceId).ifPresent(ws -> {
            WorkspaceDto dto = new WorkspaceDto();
            dto.setId(ws.getWorkspaceId());
            dto.setName(ws.getName());
            dto.setDescription(ws.getDescription());
            snap.setWorkspaces(List.of(dto));
        });
        if (snap.getWorkspaces() == null) {
            snap.setWorkspaces(List.of());
        }

        snap.setActiveWorkspaceId(workspaceId);

        // Teams
        List<CeBsTeam> teams = teamRepo.findByWorkspaceId(workspaceId);
        Set<String> teamIds = teams.stream().map(CeBsTeam::getTeamId).collect(Collectors.toSet());
        snap.setTeams(teams.stream().map(t -> {
            TeamDto dto = new TeamDto();
            dto.setId(t.getTeamId());
            dto.setName(t.getName());
            dto.setWorkspaceId(t.getWorkspaceId());
            // agentPoolIds filled below
            dto.setAgentPoolIds(new ArrayList<>());
            return dto;
        }).collect(Collectors.toList()));

        // Agent Pools
        List<AgentPoolDto> poolDtos = new ArrayList<>();
        Map<String, TeamDto> teamMap = snap.getTeams().stream()
                .collect(Collectors.toMap(TeamDto::getId, t -> t));
        for (String teamId : teamIds) {
            for (CeBsAgentPool p : agentPoolRepo.findByTeamId(teamId)) {
                AgentPoolDto dto = new AgentPoolDto();
                dto.setId(p.getPoolId());
                dto.setName(p.getName());
                dto.setTeamId(p.getTeamId());
                dto.setAgentIds(new ArrayList<>());
                poolDtos.add(dto);
                // Wire back into team
                TeamDto td = teamMap.get(teamId);
                if (td != null) td.getAgentPoolIds().add(p.getPoolId());
            }
        }
        snap.setAgentPools(poolDtos);

        // Skills
        snap.setSkills(skillRepo.findByWorkspaceId(workspaceId).stream().map(s -> {
            SkillDto dto = new SkillDto();
            dto.setId(s.getSkillId());
            dto.setName(s.getName());
            dto.setWorkspaceId(s.getWorkspaceId());
            dto.setLanguage(s.getLanguage());
            dto.setSource(s.getSource());
            dto.setInputSchema(fromJson(s.getInputSchema()));
            dto.setOutputSchema(fromJson(s.getOutputSchema()));
            return dto;
        }).collect(Collectors.toList()));

        // Agents
        Map<String, AgentPoolDto> poolMap = poolDtos.stream()
                .collect(Collectors.toMap(AgentPoolDto::getId, p -> p));
        List<AgentDto> agentDtos = new ArrayList<>();
        for (AgentPoolDto pool : poolDtos) {
            for (CeBsAgent a : agentRepo.findByPoolId(pool.getId())) {
                AgentDto dto = new AgentDto();
                dto.setId(a.getAgentId());
                dto.setName(a.getName());
                dto.setPoolId(a.getPoolId());
                dto.setModel(a.getModel());
                dto.setProvider(a.getProvider());
                dto.setSystemPrompt(a.getSystemPrompt());
                dto.setUserPrompt(a.getUserPrompt());
                dto.setInputSchema(fromJson(a.getInputSchema()));
                dto.setOutputSchema(fromJson(a.getOutputSchema()));
                dto.setStrictInput(a.getStrictInput());
                dto.setStrictOutput(a.getStrictOutput());
                // Skill attachments
                dto.setAttachedSkillIds(
                        agentSkillRepo.findByAgentId(a.getAgentId()).stream()
                                .map(CeBsAgentSkill::getSkillId)
                                .collect(Collectors.toList())
                );
                agentDtos.add(dto);
                // Wire back into pool
                pool.getAgentIds().add(a.getAgentId());
            }
        }
        snap.setAgents(agentDtos);

        // Workflows
        snap.setWorkflows(workflowRepo.findByWorkspaceId(workspaceId).stream().map(w -> {
            WorkflowDto dto = new WorkflowDto();
            dto.setId(w.getWorkflowId());
            dto.setName(w.getName());
            dto.setTeamId(w.getTeamId());
            dto.setDescription(w.getDescription());
            dto.setNodes(fromJson(w.getNodes()));
            dto.setEdges(fromJson(w.getEdges()));
            dto.setSubBlockValues(fromJson(w.getSubBlockValues()));
            dto.setMetadata(fromJson(w.getMetadata()));
            dto.setCreatedAt(w.getCreatedAt() != null ? w.getCreatedAt().toString() : null);
            dto.setUpdatedAt(w.getUpdatedAt() != null ? w.getUpdatedAt().toString() : null);
            return dto;
        }).collect(Collectors.toList()));

        // Set activeWorkflowId to first workflow if available
        if (!snap.getWorkflows().isEmpty()) {
            snap.setActiveWorkflowId(snap.getWorkflows().get(0).getId());
        }

        // LLM Config
        llmConfigRepo.findById(workspaceId).ifPresent(cfg ->
            snap.setLlmConfig(fromJson(cfg.getConfigJson()))
        );

        return snap;
    }

    // ───────────────────────────────────────────── helpers ──────────────

    private String toJson(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return null;
        }
    }

    private Object fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON", e);
            return json;
        }
    }

    private <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }
}
