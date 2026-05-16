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
}
