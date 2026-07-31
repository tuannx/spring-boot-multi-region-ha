package com.multiregion.cassandra.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "com.multiregion.cassandra",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CassandraCaseArchitectureTest {

    @ArchTest
    static final ArchRule catalogBoundaries = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Web").definedBy("..catalog.web..")
            .layer("Application").definedBy("..catalog.application..")
            .layer("Ports").definedBy("..catalog.port..")
            .layer("Domain").definedBy("..catalog.domain..")
            .layer("Persistence").definedBy("..catalog.persistence..")
            .whereLayer("Web").mayOnlyAccessLayers("Application", "Domain")
            .whereLayer("Application").mayOnlyAccessLayers("Ports", "Domain")
            .whereLayer("Ports").mayOnlyAccessLayers("Domain")
            .whereLayer("Persistence").mayOnlyAccessLayers("Ports", "Domain");
}
