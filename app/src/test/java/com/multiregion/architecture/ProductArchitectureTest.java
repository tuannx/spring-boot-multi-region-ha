package com.multiregion.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.multiregion.product",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ProductArchitectureTest {

    @ArchTest
    static final ArchRule product_core_does_not_depend_on_platform = noClasses()
            .that().resideInAnyPackage(
                    "..product.domain..",
                    "..product.port..",
                    "..product.application..")
            .should().dependOnClassesThat().resideInAPackage("..platform..");
}
