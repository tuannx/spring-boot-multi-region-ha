package com.multiregion.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.multiregion.queue",
        importOptions = ImportOption.DoNotIncludeTests.class)
class QueueArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_framework_independent = noClasses()
            .that().resideInAPackage("..queue.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.queue.domain..");

    @ArchTest
    static final ArchRule ports_only_depend_on_domain = noClasses()
            .that().resideInAPackage("..queue.port..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.queue.domain..",
                    "com.multiregion.queue.port..");

    @ArchTest
    static final ArchRule application_only_depends_on_domain_and_ports = noClasses()
            .that().resideInAPackage("..queue.application..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "org.slf4j..",
                    "com.multiregion.queue.application..",
                    "com.multiregion.queue.domain..",
                    "com.multiregion.queue.port..");

    @ArchTest
    static final ArchRule core_does_not_depend_on_adapters = noClasses()
            .that().resideInAnyPackage(
                    "..queue.domain..",
                    "..queue.port..",
                    "..queue.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..queue.config..",
                    "..queue.logging..",
                    "..queue.persistence..",
                    "..queue.rabbitmq..",
                    "..queue.web..");
}
