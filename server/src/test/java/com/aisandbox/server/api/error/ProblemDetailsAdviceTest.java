package com.aisandbox.server.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;

/**
 * UC11 § AC6 — direct unit test for {@link ProblemDetailsAdvice#handleAny(Throwable)}.
 *
 * <p>Companion to {@link com.aisandbox.server.api.ProblemDetailsAdviceTest} (the
 * broader suite covering the mapped {@code @ExceptionHandler} methods). This
 * focused file pins exactly two contract points that UC11 cared about:
 *
 * <ol>
 *   <li><b>Shape</b>: an exception that no other handler claims produces a
 *       {@link ProblemDetail} with status 500, {@code code: internal_error},
 *       and a {@code type} URI ending in {@code /internal_error}.</li>
 *   <li><b>Audit trail</b>: the WARN line {@code "Unmapped exception in REST
 *       flow: ..."} fires through the {@link ProblemDetailsAdvice} SLF4J
 *       logger. That line is the operational signal that a domain-specific
 *       handler is missing; UC11 § AC5 verifies the SAME line is SILENT for
 *       the five enrollment exceptions when the new
 *       {@link com.aisandbox.server.enrollment.api.EnrollmentWebExceptionHandler}
 *       catches them. Anchoring the positive case here is the regression
 *       guard — if the line stops firing (e.g. someone re-tags the logger
 *       level or rephrases the message), the AC5 assertion would silently
 *       go vacuous.</li>
 * </ol>
 *
 * <p>The test attaches a Logback {@link ListAppender} to the
 * {@code ProblemDetailsAdvice} category so the assertions don't depend on
 * the test runtime's logback config.
 */
class ProblemDetailsAdviceTest {

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice();

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level priorLevel;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(ProblemDetailsAdvice.class);
        priorLevel = logger.getLevel();
        appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void detach() {
        if (logger != null && appender != null) {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(priorLevel);
        }
    }

    @Test
    void handleAny_produces_internal_error_problem_detail_and_logs_unmapped_warning() {
        ProblemDetail pd = advice.handleAny(new RuntimeException("not in any specific handler"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getProperties()).containsEntry("code", "internal_error");
        assertThat(pd.getType().toString())
                .as("type URI MUST resolve under the documented problem-base + code")
                .endsWith("/internal_error");
        assertThat(pd.getDetail()).contains("not in any specific handler");

        assertThat(appender.list)
                .as("UC11 § AC6 — the unmapped-exception log line MUST fire for genuinely unmapped throwables")
                .anySatisfy(evt -> {
                    assertThat(evt.getLevel()).isEqualTo(Level.WARN);
                    assertThat(evt.getFormattedMessage())
                            .contains("Unmapped exception in REST flow")
                            .contains("not in any specific handler");
                });
    }
}
