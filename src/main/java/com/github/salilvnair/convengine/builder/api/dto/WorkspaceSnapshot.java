package com.github.salilvnair.convengine.builder.api.dto;

import lombok.Data;

import java.util.List;

/**
 * Full workspace snapshot DTO — mirrors the Zustand store shape on the
 * front-end so save/load round-trips cleanly.
 *
 * Used for both the request body of {@code POST /sync} (save) and the
 * response body of {@code GET /workspace/{id}} (load).
 */
@Data
public class WorkspaceSnapshot {

    private String activeWorkspaceId;
    private String activeWorkflowId;
    private List<WorkspaceDto> workspaces;
    private List<TeamDto> teams;
    private List<AgentPoolDto> agentPools;
    private List<AgentDto> agents;
    private List<SkillDto> skills;
    private List<WorkflowDto> workflows;

    @Data
    public static class WorkspaceDto {
        private String id;
        private String name;
        private String description;
    }

    @Data
    public static class TeamDto {
        private String id;
        private String name;
        private String workspaceId;
        private List<String> agentPoolIds;
    }

    @Data
    public static class AgentPoolDto {
        private String id;
        private String name;
        private String teamId;
        private List<String> agentIds;
    }

    @Data
    public static class AgentDto {
        private String id;
        private String name;
        private String poolId;
        private String model;
        private String provider;
        private String systemPrompt;
        private String userPrompt;
        private Object inputSchema;
        private Object outputSchema;
        private Boolean strictInput;
        private Boolean strictOutput;
        private List<String> attachedSkillIds;
    }

    @Data
    public static class SkillDto {
        private String id;
        private String name;
        private String workspaceId;
        private String language;
        private String source;
        private Object inputSchema;
        private Object outputSchema;
    }

    @Data
    public static class WorkflowDto {
        private String id;
        private String name;
        private String teamId;
        private String description;
        private Object nodes;
        private Object edges;
        private Object subBlockValues;
        private Object metadata;
        private String createdAt;
        private String updatedAt;
    }
}
