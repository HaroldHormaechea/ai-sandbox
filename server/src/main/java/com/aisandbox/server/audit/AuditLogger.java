package com.aisandbox.server.audit;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Single emission point for audit events. Wraps an SLF4J logger named
 * {@code audit}; Logback routes that logger to both the JSON console
 * (journald) and the file appender at {@code /var/log/ai-sandbox-server/audit.log}.
 *
 * <p>This class is the only API allowed to write audit lines. The
 * {@link #logEvent} method intentionally does not accept any parameter
 * named for PEM bytes, private keys, or passwords — secret material can
 * never appear in audit output (AC15, AC43).
 *
 * <p>Failure-mode policy: every public method swallows {@link RuntimeException}
 * so an audit-side glitch never propagates into the live request path.
 */
@Component
public class AuditLogger {

    private static final Logger AUDIT = LoggerFactory.getLogger("audit");

    /**
     * Emit an audit line. The variadic {@code fields} pairs are written to
     * the MDC for the duration of the log call (so the JSON encoder picks
     * them up) and removed afterwards.
     *
     * @param action  the audit action enum
     * @param outcome short string — {@code "ok"}, {@code "denied"}, error code etc.
     * @param fields  alternating key/value pairs (target, n, stream_id, …);
     *                an odd count throws; values are stringified via {@code toString}.
     */
    public void logEvent(AuditAction action, String outcome, Object... fields) {
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("logEvent requires an even number of key/value args");
        }
        try {
            MDC.put("action", action.wire());
            MDC.put("outcome", outcome);
            for (int i = 0; i < fields.length; i += 2) {
                MDC.put(String.valueOf(fields[i]), String.valueOf(fields[i + 1]));
            }
            AUDIT.info("{}", action.wire());
        } catch (RuntimeException re) {
            // Audit-side failure must never propagate to the request path.
            // Surface to the operational stream via a separate logger so
            // it's still visible in journald.
            LoggerFactory.getLogger(AuditLogger.class).warn("audit write failed: {}", re.toString());
        } finally {
            MDC.remove("action");
            MDC.remove("outcome");
            for (int i = 0; i < fields.length; i += 2) {
                MDC.remove(String.valueOf(fields[i]));
            }
        }
    }

    /** Convenience overload accepting a pre-built map. */
    public void logEvent(AuditAction action, String outcome, Map<String, ?> fields) {
        Object[] flat = new Object[fields.size() * 2];
        int i = 0;
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            flat[i++] = e.getKey();
            flat[i++] = e.getValue();
        }
        logEvent(action, outcome, flat);
    }
}
