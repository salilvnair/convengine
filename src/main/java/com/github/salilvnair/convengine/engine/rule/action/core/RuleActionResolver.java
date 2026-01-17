package com.github.salilvnair.convengine.engine.rule.action.core;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;

public interface RuleActionResolver {

    String action();

    void resolve(EngineSession session, CeRule rule);
}
