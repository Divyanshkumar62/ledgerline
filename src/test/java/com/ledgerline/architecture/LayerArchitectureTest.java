package com.ledgerline.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
}
