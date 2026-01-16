package com.github.salilvnair.convengine.engine.pipeline.annotation;

import com.github.salilvnair.convengine.engine.pipeline.EngineStep;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MustRunBefore {
    Class<? extends EngineStep>[] value();
}
