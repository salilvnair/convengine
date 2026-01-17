package com.github.salilvnair.convengine.engine.rule.type.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;

public interface RuleTypeResolver {

    String type();

    boolean resolve(EngineSession session, CeRule rule);
}
