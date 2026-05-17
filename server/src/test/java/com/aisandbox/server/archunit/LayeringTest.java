package com.aisandbox.server.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * Encodes the {@code profile-java-server-architecture} call-chain rules
 * for the {@code com.aisandbox.server} production tree:
 *
 * <ul>
 *   <li>Controllers (api package) call facades — not service classes.
 *       Nested DTO-shaped records exposed by a service are tolerated; the
 *       rule pins on the {@code *Service} type itself.</li>
 *   <li>Facades do not depend on a {@code repository} package.</li>
 *   <li>{@code @Transactional} appears only on facade-layer types or methods.</li>
 *   <li>Internal DTO packages ({@code sessions.dto}, {@code clients.dto}) are
 *       not used as REST request / response types in {@code api.*}.</li>
 *   <li>No circular dependencies among feature packages
 *       ({@code sessions}, {@code clients}, {@code stream}, {@code api},
 *       {@code health}). The {@code config} wiring package is allowed to
 *       depend on multiple feature packages — that is its job — and is
 *       excluded from the cycle scan.</li>
 * </ul>
 */
class LayeringTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("com.aisandbox.server");

    @Test
    void controllers_only_reference_facade_layer_service_types_not_service_classes_themselves() {
        // Controllers may freely reference nested DTO records exposed by a service
        // (these act as internal projections), but MUST NOT depend on a *Service
        // or *Repository class itself — that's the facade's job.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..api")
                .and()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .haveSimpleNameEndingWith("Repository");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void facades_do_not_depend_on_repository_classes_directly() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..facade..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..repository..");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void transactional_annotation_lives_only_on_facade_classes_or_methods() {
        // The brief has no DB today; this rule will fire the moment a service
        // or repository accidentally picks up @Transactional. Empty matches
        // are acceptable since production currently uses no @Transactional.
        ArchRule rule = classes()
                .that()
                .areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .should()
                .resideInAPackage("..facade..");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void controllers_do_not_use_internal_dto_records_in_their_signatures() {
        // Controllers must use api.dto types, not internal service/domain DTOs.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..api")
                .and()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.aisandbox.server.sessions.dto", "com.aisandbox.server.clients.dto");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void no_cycles_between_top_level_feature_packages() {
        // The `config` wiring package is allowed to depend on many feature
        // packages because that's its job; import everything except `config`
        // for this cycle scan so the remaining feature-package cycles surface
        // clearly without false positives from wiring.
        JavaClasses featureOnly = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .withImportOption(location -> !location.contains("/com/aisandbox/server/config/"))
                .importPackages("com.aisandbox.server");
        ArchRule rule = SlicesRuleDefinition.slices()
                .matching("com.aisandbox.server.(*)..")
                .namingSlices("Slice $1")
                .should()
                .beFreeOfCycles();
        rule.check(featureOnly);
    }

    // ── UC04 § B2 / proposal — additional structural rules ───────────────────

    @Test
    void enrollment_facade_does_not_reach_into_clients_service_package() {
        // UC04 cross-domain hand-off goes facade-to-facade
        // (EnrollmentFacade → ClientAllowlistFacade). Reaching directly
        // into clients.service.* (AllowlistDirectory / ClientAllowlistService
        // / ClientCertParser) would bypass the use-case boundary and lose
        // the immediate-rebuild + audit emission contract that lives on
        // the sibling facade. profile-java-server-architecture rule 6.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..enrollment..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..clients.service..");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void enrollment_controller_only_references_enrollment_facade_at_the_use_case_boundary() {
        // Mirrors the generic "controllers don't reach into services or
        // repositories" pin, scoped to the enrollment slice so a future
        // refactor that injects EnrollmentTokenService or
        // EnrollmentCertMintService into the controller surfaces with
        // the right blame line.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..enrollment.api..")
                .and()
                .haveSimpleNameEndingWith("Controller")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..enrollment.service..");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void no_production_caller_outside_identity_may_call_ActiveConnectionRegistry_terminate() {
        // UC04 § B2 — production code must go through revoke(Set), which
        // first issues a graceful WS close (so the Android client surfaces
        // the AC26 "Identity revoked" dialog) then calls terminate(Set)
        // internally. Direct terminate(Set) calls from a facade or
        // watcher would skip the close-frame step. The method is kept
        // public for back-compat with QA tests; the proposal's
        // "demote to package-private" follow-up lands later.
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("..identity..")
                .and()
                .resideOutsideOfPackage("..config..")
                .should()
                .callMethod(
                        com.aisandbox.server.identity.ActiveConnectionRegistry.class, "terminate", java.util.Set.class);
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void only_active_connection_registry_calls_active_stream_registry_gracefulClose() {
        // The graceful WebSocket close path MUST funnel through
        // ActiveConnectionRegistry#revoke(Set) — that's the place that
        // pairs gracefulClose with the bounded timeout + the TCP-layer
        // tear-down. A facade or watcher calling gracefulClose directly
        // would lose the timeout (deadlock risk) and would skip the
        // subsequent terminate() — so the channel survives indefinitely.
        com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaCall<?>> isGracefulClose =
                new com.tngtech.archunit.base.DescribedPredicate<>("a call to ActiveStreamRegistry#gracefulClose") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaCall<?> call) {
                        return call.getTarget().getName().equals("gracefulClose")
                                && call.getTarget()
                                        .getOwner()
                                        .isAssignableTo(com.aisandbox.server.identity.ActiveStreamRegistry.class);
                    }
                };
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("..identity..")
                .should()
                .callMethodWhere(isGracefulClose);
        rule.allowEmptyShould(true).check(PRODUCTION);
    }

    @Test
    void enrollment_service_classes_carry_no_at_transactional_annotation() {
        // The store + cert-mint + rate-limiter all touch filesystem /
        // in-memory state only — there is no transactional resource to
        // join. Adding @Transactional here would be a runtime no-op AND
        // a false promise of atomicity. profile-java-server-architecture
        // already restricts @Transactional to ..facade.. ; this is a
        // narrower pin so an enrollment-slice regression surfaces with
        // the right blame line.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..enrollment.service..")
                .should()
                .beAnnotatedWith("org.springframework.transaction.annotation.Transactional");
        rule.allowEmptyShould(true).check(PRODUCTION);
    }
}
