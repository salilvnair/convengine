package com.github.salilvnair.convengine.engine.task;

import com.github.salilvnair.ccf.util.commonutil.lang.ReflectionUtil;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class CeTaskExecutor {
    private final ApplicationContext applicationContext;

    public CeTaskExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public Object execute(String beanName, String methodNameString, EngineSession session) {
        try {
            List<String> methodNames = splitMethods(methodNameString);
            CeTask task = (CeTask) applicationContext.getBean(beanName);
            Object[] returnedObjects = new Object[methodNames.size()];
            int i = 0;
            for (String methodName : methodNames) {
                returnedObjects[i] = ReflectionUtil.invokeMethod(task, methodName, session);
                i++;
            }
            return returnedObjects;
        } catch (Exception ex) {
            log.error(ex.getLocalizedMessage(), ex);
            return null;
        }
    }

    public List<String> splitMethods(String methodNameString) {
        List<String> methodNames = new ArrayList<>();
        if (methodNameString != null) {
            methodNames = Arrays.stream(methodNameString.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        return methodNames;
    }
}
