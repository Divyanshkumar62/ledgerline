package com.ledgerline.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Enforces clean hexagonal/onion layered architecture:
 * Domain -> Application -> Infrastructure
 */
@AnalyzeClasses(packages = "com.ledgerline", importOptions = ImportOption.DoNotIncludeTests.class)
public class LayerArchitectureTest {

    @ArchTest
    public static final ArchRule layeringRule = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.ledgerline.domain..")
            .layer("Application").definedBy("com.ledgerline.application..")
            .layer("Infrastructure").definedBy("com.ledgerline.infrastructure..")

            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure")
            .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer();

    /** The domain may carry JPA mapping annotations (jakarta.persistence) but never Spring. */
    @ArchTest
    public static final ArchRule domainIsFreeOfSpring = noClasses()
            .that().resideInAPackage("com.ledgerline.domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /** Use cases know nothing about how persistence or HTTP are implemented. */
    @ArchTest
    public static final ArchRule applicationIsFreeOfPersistenceAndWeb = noClasses()
            .that().resideInAPackage("com.ledgerline.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.hibernate..", "org.springframework.data..",
                    "org.springframework.jdbc..", "org.springframework.web..", "org.springframework.http..");
}
