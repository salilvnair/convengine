package com.github.salilvnair.convengine.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SemanticModelStudioConfigResponse {
    private boolean success;
    private List<String> editableSections = new ArrayList<>();
    private List<String> dbManagedSections = new ArrayList<>();
    private String note;
}
