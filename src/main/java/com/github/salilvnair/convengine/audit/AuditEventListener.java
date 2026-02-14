package com.github.salilvnair.convengine.audit;

import com.github.salilvnair.convengine.entity.CeAudit;

public interface AuditEventListener {
    void onAudit(CeAudit audit);
}
