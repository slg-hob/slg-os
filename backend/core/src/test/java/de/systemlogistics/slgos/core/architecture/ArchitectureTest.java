package de.systemlogistics.slgos.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Layering rules of PRD-0003 section 9: the dependency direction between security, session and the rest. */
@AnalyzeClasses(packages = "de.systemlogistics.slgos.core", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String SESSION = "de.systemlogistics.slgos.core.session..";
    private static final String GATEWAY = "de.systemlogistics.slgos.core.gateway..";

    @ArchTest
    static final ArchRule securityDoesNotDependOnSessionOrGateway = noClasses().that()
            .resideInAPackage("de.systemlogistics.slgos.core.security..")
            .should().dependOnClassesThat().resideInAnyPackage(SESSION, GATEWAY, "de.systemlogistics.slgos.core.auth..",
                    "de.systemlogistics.slgos.core.audit..", "de.systemlogistics.slgos.core.health..");

    @ArchTest
    static final ArchRule onlySessionClassesTouchTheTokenVault = noClasses().that()
            .resideOutsideOfPackage(SESSION)
            .should().dependOnClassesThat().haveSimpleName("TokenVault");

    @ArchTest
    static final ArchRule webControllersDoNotUseSqlDirectly = noClasses().that()
            .resideInAnyPackage("de.systemlogistics.slgos.core.auth..", "de.systemlogistics.slgos.core.health..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.jdbc.core.simple.JdbcClient")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.sql.DataSource");

    @ArchTest
    static final ArchRule theSessionTableIsOnlyAccessedFromTheSessionPackage = noClasses().that()
            .resideOutsideOfPackage(SESSION)
            .should().dependOnClassesThat().haveSimpleName("SessionStore");

    @ArchTest
    static final ArchRule securityOnlyDependsOnConfigAndLibraries = classes().that().resideInAPackage("de.systemlogistics.slgos.core.security..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("de.systemlogistics.slgos.core.security..",
                    "de.systemlogistics.slgos.core.config..", "java..", "javax..", "jakarta..", "org..", "com.nimbusds..",
                    "tools.jackson..", "com.fasterxml.jackson..");
}
