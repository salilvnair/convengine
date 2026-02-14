package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExperimentalSqlGenerationResponse {
    private boolean success;
    private String sql;
    private List<String> warnings = new ArrayList<>();
    private String note;
}
