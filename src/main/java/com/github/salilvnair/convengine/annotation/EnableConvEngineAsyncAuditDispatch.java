package com.github.salilvnair.convengine.annotation;

import com.github.salilvnair.convengine.config.feature.ConvEngineAsyncAuditDispatchMarker;
import org.springframework.context.annotation.Import;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ConvEngineAsyncAuditDispatchMarker.class)
public @interface EnableConvEngineAsyncAuditDispatch {
}
