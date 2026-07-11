package com.multiregion.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.multiregion.platform",
        importOptions = ImportOption.DoNotIncludeTests.class)
class PlatformFailoverArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_pure_java = noClasses()
            .that().resideInAPackage("..platform.failover.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.platform.failover.domain..");

    @ArchTest
    static final ArchRule ports_only_depend_on_java_and_domain = noClasses()
            .that().resideInAPackage("..platform.failover.port..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.platform.failover.domain..",
                    "com.multiregion.platform.failover.port..");

    @ArchTest
    static final ArchRule application_only_depends_on_java_domain_and_ports = noClasses()
            .that().resideInAPackage("..platform.failover.application..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.platform.failover.application..",
                    "com.multiregion.platform.failover.domain..",
                    "com.multiregion.platform.failover.port..");

    @ArchTest
    static final ArchRule web_and_scheduling_do_not_depend_on_jdbc_adapter = noClasses()
            .that().resideInAnyPackage(
                    "..platform.web..",
                    "..platform.failover.scheduling..")
            .should().dependOnClassesThat().resideInAPackage("..platform.failover.jdbc..");

    @ArchTest
    static final ArchRule core_does_not_depend_on_adapters = noClasses()
            .that().resideInAnyPackage(
                    "..platform.failover.domain..",
                    "..platform.failover.port..",
                    "..platform.failover.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..platform.failover.config..",
                    "..platform.database..",
                    "..platform.failover.jdbc..",
                    "..platform.failover.routing..",
                    "..platform.failover.scheduling..",
                    "..platform.routing..",
                    "..platform.web..");
}
