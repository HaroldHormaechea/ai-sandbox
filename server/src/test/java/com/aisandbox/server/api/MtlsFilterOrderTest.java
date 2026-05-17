package com.aisandbox.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.identity.ClientIdentityExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;

/**
 * UC04 § B2 — runtime invariant: {@link MtlsEnforcementFilter} MUST run
 * strictly AFTER {@link ClientIdentityExtractor}. The extractor writes
 * the {@code ATTR} attribute that the enforcer reads; ordering inversion
 * means the enforcer rejects every request as anonymous because the
 * ATTR is not yet populated.
 *
 * <p>This is a runtime assertion (not an ArchUnit rule) because the
 * constraint is about Spring's filter chain ordering — purely a numeric
 * relationship between two annotation values.
 */
class MtlsFilterOrderTest {

    @Test
    void enforcer_order_is_strictly_higher_than_extractor_order() {
        Order extractorOrder = AnnotationUtils.findAnnotation(ClientIdentityExtractor.class, Order.class);
        Order enforcerOrder = AnnotationUtils.findAnnotation(MtlsEnforcementFilter.class, Order.class);

        assertThat(extractorOrder)
                .as("ClientIdentityExtractor must carry @Order")
                .isNotNull();
        assertThat(enforcerOrder).as("MtlsEnforcementFilter must carry @Order").isNotNull();

        // Lower @Order numbers run first in Spring's chain. Enforcer must
        // therefore have a HIGHER numeric value than the extractor.
        assertThat(enforcerOrder.value())
                .as("MtlsEnforcementFilter must run AFTER ClientIdentityExtractor")
                .isGreaterThan(extractorOrder.value());
    }
}
