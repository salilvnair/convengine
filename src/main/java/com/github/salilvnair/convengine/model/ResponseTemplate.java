package com.github.salilvnair.convengine.model;

import com.github.salilvnair.convengine.entity.CeResponse;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ResponseTemplate {
    private String outputFormat;
    private String responseType;
    private String exactText;
    private String derivationHint;
    private String jsonSchema;




    public static ResponseTemplate initFrom(CeResponse response) {
        return ResponseTemplate.builder()
                .outputFormat(response.getOutputFormat())
                .responseType(response.getResponseType())
                .exactText(response.getExactText())
                .derivationHint(response.getDerivationHint())
                .jsonSchema(response.getJsonSchema())
                .build();
    }

    public static ResponseTemplate initFrom(String derivationHint, String outputFormat) {
        return ResponseTemplate.builder()
                .outputFormat(outputFormat)
                .derivationHint(derivationHint)
                .build();
    }
}
