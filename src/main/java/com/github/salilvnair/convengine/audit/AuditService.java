package com.github.salilvnair.convengine.audit;

import java.util.UUID;

public interface AuditService {
    void audit(String stage, UUID conversationId, String payloadJson);
}
