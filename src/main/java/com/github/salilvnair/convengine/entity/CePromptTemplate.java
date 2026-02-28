package com.github.salilvnair.convengine.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.type.InteractionMode;
import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;


@Entity
@Table(name = "ce_prompt_template")
@Data
public class CePromptTemplate {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Id
    @Column(name = "template_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long templateId;
    @Column(name = "intent_code", nullable = false)
    private String intentCode;
    @Column(name = "response_type")
    private String responseType;
    @Column(name = "state_code", nullable = false)
    private String stateCode;
    @Column(name = "system_prompt")
    private String systemPrompt;
    @Column(name = "user_prompt")
    private String userPrompt;
    @Column(name = "temperature")
    private Double temperature;
    @Column(name = "interaction_mode")
    private String interactionMode;
    @Column(name = "interaction_contract")
    private String interactionContract;
    @Column(name = "enabled")
    private boolean enabled;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public InteractionMode resolvedInteractionMode() {
        return InteractionMode.fromDbValue(interactionMode).orElse(InteractionMode.NORMAL);
    }

    public boolean hasInteractionSemantics() {
        return InteractionMode.fromDbValue(interactionMode).isPresent()
                || interactionContractNode() != null;
    }

    public boolean allowsAffirmAction() {
        Boolean contractValue = contractAllows("affirm");
        if (contractValue != null) {
            return contractValue;
        }
        return switch (resolvedInteractionMode()) {
            case CONFIRM -> true;
            default -> false;
        };
    }

    public boolean allowsEditAction() {
        Boolean contractValue = contractAllows("edit");
        if (contractValue != null) {
            return contractValue;
        }
        return switch (resolvedInteractionMode()) {
            case CONFIRM, REVIEW -> true;
            default -> false;
        };
    }

    public boolean allowsRetryAction() {
        Boolean contractValue = contractAllows("retry");
        if (contractValue != null) {
            return contractValue;
        }
        return switch (resolvedInteractionMode()) {
            case ERROR, PROCESSING -> true;
            default -> false;
        };
    }

    public boolean allowsResetAction() {
        Boolean contractValue = contractAllows("reset");
        if (contractValue != null) {
            return contractValue;
        }
        return switch (resolvedInteractionMode()) {
            case COLLECT, CONFIRM, REVIEW, ERROR -> true;
            default -> false;
        };
    }

    public boolean expectsStructuredInputValue() {
        Boolean contractValue = contractExpects("structured_input");
        if (contractValue != null) {
            return contractValue;
        }
        return switch (resolvedInteractionMode()) {
            case COLLECT -> true;
            default -> false;
        };
    }

    private Boolean contractAllows(String capability) {
        JsonNode root = interactionContractNode();
        if (root == null) {
            return null;
        }
        JsonNode allows = root.get("allows");
        if (allows == null || !allows.isArray()) {
            return null;
        }
        for (JsonNode node : allows) {
            if (node.isTextual() && capability.equalsIgnoreCase(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private Boolean contractExpects(String expectation) {
        JsonNode root = interactionContractNode();
        if (root == null) {
            return null;
        }
        JsonNode expects = root.get("expects");
        if (expects == null || !expects.isArray()) {
            return null;
        }
        for (JsonNode node : expects) {
            if (node.isTextual() && expectation.equalsIgnoreCase(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode interactionContractNode() {
        if (interactionContract == null || interactionContract.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readTree(interactionContract);
        } catch (Exception ignored) {
            return null;
        }
    }
}
