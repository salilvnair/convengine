package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ce_mcp_user_feedback")
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CeMcpUserFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_id")
    private Long feedbackId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "feedback_type", nullable = false, length = 32)
    private String feedbackType;

    @Column(name = "message_id", length = 128)
    private String messageId;

    @Column(name = "intent_code", length = 255)
    private String intentCode;

    @Column(name = "state_code", length = 255)
    private String stateCode;

    @Column(name = "user_query")
    private String userQuery;

    @Column(name = "assistant_response")
    private String assistantResponse;

    @Column(name = "mcp_tool_code", length = 255)
    private String mcpToolCode;

    @Column(name = "captured_query_knowledge_count")
    private Integer capturedQueryKnowledgeCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_query_knowledge_json")
    private String appliedQueryKnowledgeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json")
    private String metadataJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (capturedQueryKnowledgeCount == null) {
            capturedQueryKnowledgeCount = 0;
        }
    }
}

