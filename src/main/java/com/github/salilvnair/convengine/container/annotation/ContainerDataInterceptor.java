package com.github.salilvnair.convengine.container.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContainerDataInterceptor {

    String intent() default "*";
    String state() default "*";

    int order() default 0;
}
