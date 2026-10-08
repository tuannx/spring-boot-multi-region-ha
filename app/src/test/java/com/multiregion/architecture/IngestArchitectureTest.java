package com.multiregion.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal guard for the ingest bounded context, mirroring
 * QueueArchitectureTest: domain stays pure, adapters never leak inward,
 * and the Ingest Service never depends on the Message Processor (queue)
 * internals -- the two services meet only at the SQS queue contract.
 */
@AnalyzeClasses(
        packages = "com.multiregion.ingest",
        importOptions = ImportOption.DoNotIncludeTests.class)
class IngestArchitectureTest {

    @ArchTest
    static final ArchRule domain_is_framework_independent = noClasses()
            .that().resideInAPackage("..ingest.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.ingest.domain..");

    @ArchTest
    static final ArchRule ports_only_depend_on_domain = noClasses()
            .that().resideInAPackage("..ingest.port..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "com.multiregion.ingest.domain..",
                    "com.multiregion.ingest.port..");

    @ArchTest
    static final ArchRule application_only_depends_on_domain_and_ports = noClasses()
            .that().resideInAPackage("..ingest.application..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..",
                    "org.slf4j..",
                    "com.multiregion.ingest.application..",
                    "com.multiregion.ingest.domain..",
                    "com.multiregion.ingest.port..");

    @ArchTest
    static final ArchRule core_does_not_depend_on_adapters_or_processor = noClasses()
            .that().resideInAnyPackage(
                    "..ingest.domain..",
                    "..ingest.port..",
                    "..ingest.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..ingest.config..",
                    "..ingest.kinesis..",
                    "..ingest.sqs..",
                    "..ingest.logging..",
                    "..ingest.web..",
                    "..queue..");
}
