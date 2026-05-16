package com.aisandbox.server.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Spring {@code @Conditional} guard used to skip the audit file appender
 * when its directory is missing or unwritable. Logback's own
 * {@code <springProfile>} block in {@code logback-spring.xml} would
 * otherwise crash boot when the file appender cannot open. We instead let
 * the application come up sans-file-appender, and let
 * {@code PropertiesValidationStartupCheck} fail-fast with a precise error
 * — the failure path is documented and recoverable.
 *
 * <p>Reads the resolved audit file path from
 * {@code ai-sandbox.server.audit.file}.
 */
public class AuditLogDirectoryCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured = context.getEnvironment().getProperty("ai-sandbox.server.audit.file");
        if (configured == null) {
            return false;
        }
        Path parent = Paths.get(configured).getParent();
        return parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
    }
}
