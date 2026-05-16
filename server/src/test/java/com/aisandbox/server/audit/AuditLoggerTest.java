package com.aisandbox.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * AC41 / AC42 — every audit event is emitted via the {@code audit} logger
 * with the action wire-name as the message and the key/value pairs
 * deposited on MDC. AC43 — no secret material is ever passed through.
 */
class AuditLoggerTest {

    private final AuditLogger logger = new AuditLogger();
    private ListAppender<ILoggingEvent> appender;
    private Logger auditLogger;

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger("audit");
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
    }

    @Test
    void emits_action_wire_name_and_mdc_fields() {
        logger.logEvent(AuditAction.CLIENT_ADD, "ok", "name", "alice", "fingerprint", "deadbeef");

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getMessage()).isEqualTo("{}");
        assertThat(event.getFormattedMessage()).isEqualTo("client_add");
        Map<String, String> mdc = event.getMDCPropertyMap();
        assertThat(mdc).containsEntry("action", "client_add");
        assertThat(mdc).containsEntry("outcome", "ok");
        assertThat(mdc).containsEntry("name", "alice");
        assertThat(mdc).containsEntry("fingerprint", "deadbeef");
    }

    @Test
    void mdc_keys_are_cleaned_up_after_each_event() {
        logger.logEvent(AuditAction.CLIENT_REMOVE, "ok", "name", "leaks");
        // After the call MDC must be wiped — otherwise subsequent unrelated
        // log lines would inherit our audit fields.
        assertThat(org.slf4j.MDC.get("name")).isNull();
        assertThat(org.slf4j.MDC.get("action")).isNull();
        assertThat(org.slf4j.MDC.get("outcome")).isNull();
    }

    @Test
    void odd_number_of_kv_args_is_rejected() {
        assertThatThrownBy(() -> logger.logEvent(AuditAction.STREAM_OPEN, "ok", "lonely"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void map_overload_writes_each_entry_to_mdc() {
        logger.logEvent(AuditAction.STREAM_CLOSE, "ok", Map.of("n", 7, "streamId", "abc123"));

        Map<String, String> mdc = appender.list.get(0).getMDCPropertyMap();
        assertThat(mdc).containsEntry("n", "7");
        assertThat(mdc).containsEntry("streamId", "abc123");
    }

    @Test
    void every_action_has_a_lowercase_wire_form() {
        for (AuditAction a : AuditAction.values()) {
            assertThat(a.wire()).isEqualTo(a.name().toLowerCase());
        }
    }
}
