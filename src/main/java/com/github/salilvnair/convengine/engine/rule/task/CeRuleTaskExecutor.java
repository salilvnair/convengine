package com.github.salilvnair.convengine.engine.rule.task;

import com.github.salilvnair.ccf.util.commonutil.lang.ReflectionUtil;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class CeRuleTaskExecutor {
    private final ApplicationContext applicationContext;

    public CeRuleTaskExecutor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    public Object execute(String beanName, String methodNameString, EngineSession session, CeRule ceRule) {
        try {
            List<String> methodNames = transformIntoMethodNamesIfFoundMultipleNames(methodNameString);
            CeRuleTask ruleTask = (CeRuleTask) applicationContext.getBean(beanName);
            Object[] returnedObjects = new Object[methodNames.size()];
            int i = 0;
            for (String methodName : methodNames) {
                returnedObjects[i] = ReflectionUtil.invokeMethod(ruleTask, methodName, session, ceRule);
                i++;
            }
            return returnedObjects;
        }
        catch (Exception ex) {
            log.error(ex.getLocalizedMessage());
        }
        return null;
    }

    public List<String> transformIntoMethodNamesIfFoundMultipleNames(String methodNameString) {
        List<String> methodNames = new ArrayList<>();
        if(methodNameString != null) {
            methodNames = Arrays.asList(methodNameString.split(","));
        }
        return methodNames;
    }
}
