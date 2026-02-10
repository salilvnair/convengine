package com.github.salilvnair.convengine.model;

import com.github.salilvnair.convengine.entity.CePromptTemplate;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PromptTemplate {
    private Long templateId;
    private String templateDesc;
    private String responseType;
    private String systemPrompt;
    private String userPrompt;
    private Double temperature;


    public static PromptTemplate initFrom(CePromptTemplate cePromptTemplate) {
        if(cePromptTemplate == null) return null;
        return PromptTemplate.builder()
                .responseType(cePromptTemplate.getResponseType())
                .systemPrompt(cePromptTemplate.getSystemPrompt())
                .userPrompt(cePromptTemplate.getUserPrompt())
                .temperature(cePromptTemplate.getTemperature())
                .templateId(cePromptTemplate.getTemplateId())
                .build();
    }

    public static PromptTemplate initFrom(String systemPrompt, String userPrompt, String responseType, String templateDesc) {
        return PromptTemplate.builder()
                .responseType(responseType)
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .templateDesc(templateDesc)
                .build();
    }

}
