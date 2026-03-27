package com.github.salilvnair.convengine.engine.response.annotation;

import com.github.salilvnair.convengine.engine.response.type.ResponseTransformType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ResponseTransformer {
    String intent();
    String state();
    ResponseTransformType responseType() default ResponseTransformType.LLM;
}
