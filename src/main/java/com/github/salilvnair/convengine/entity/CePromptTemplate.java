package com.github.salilvnair.convengine.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;


@Entity
@Table(name = "ce_prompt_template")
@Data
public class CePromptTemplate {
    @Id
    @Column(name = "template_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long templateId;
    @Column(name = "intent_code")
    private String intentCode;
    private String purpose;
    @Column(name = "system_prompt")
    private String systemPrompt;
    @Column(name = "user_prompt")
    private String userPrompt;
    private Double temperature;
    private Boolean enabled;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
