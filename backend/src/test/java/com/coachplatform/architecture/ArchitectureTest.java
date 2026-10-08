package com.coachplatform.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.coachplatform.tenant.CrossTenantAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Enforces the architecture rules documented in CLAUDE.md. Only production classes are analysed. */
class ArchitectureTest {

    private static final String ROOT = "com.coachplatform";

    /** Functional modules: they may talk to each other ONLY through public *Service classes or their ..api.. package. */
    private static final List<String> MODULES = List.of("auth", "coach", "students", "billing", "scheduling", "notifications");
    /** Infrastructure: usable by everyone, must not depend on functional modules. */
    private static final List<String> INFRASTRUCTURE = List.of("common", "tenant", "security");

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages(ROOT);
    }

    // ---- domain purity -------------------------------------------------------------------------

    @Test
    void domainDoesNotDependOnPersistenceOrWeb() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.persistence..", "org.hibernate..", "org.springframework.web..",
                        "org.springframework.http..", "org.springframework.data..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void domainDoesNotDependOnControllersOrEntities() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat(simpleNameEndingWith("Controller")
                        .or(DescribedPredicate.describe("are JPA entities",
                                (JavaClass c) -> c.isAnnotatedWith("jakarta.persistence.Entity")
                                        || c.isAnnotatedWith("jakarta.persistence.MappedSuperclass"))))
                .allowEmptyShould(true)
                .check(classes);
    }

    // ---- module boundaries ---------------------------------------------------------------------

    @Test
    void modulesOnlyUseOtherModulesThroughPublicServices() {
        for (String module : MODULES) {
            for (String other : MODULES) {
                if (module.equals(other)) {
                    continue;
                }
                noClasses().that().resideInAPackage(pkg(module))
                        .should().dependOnClassesThat(
                                resideInAPackage(pkg(other))
                                        .and(not(simpleNameEndingWith("Service")))
                                        .and(not(resideInAPackage(pkg(other + ".api")))))
                        .because("module '" + module + "' may only use '" + other + "' via its *Service or ..api.. types")
                        .allowEmptyShould(true)
                        .check(classes);
            }
        }
    }

    @Test
    void infrastructureDoesNotDependOnFunctionalModules() {
        for (String infra : INFRASTRUCTURE) {
            for (String module : MODULES) {
                noClasses().that().resideInAPackage(pkg(infra))
                        .should().dependOnClassesThat().resideInAPackage(pkg(module))
                        .allowEmptyShould(true)
                        .check(classes);
            }
        }
    }

    // ---- public services expose no entities ----------------------------------------------------

    @Test
    void publicServicesReturnOnlySimpleValuesRecordsOrEnums() {
        methods().that().arePublic().and().areDeclaredInClassesThat(
                        simpleNameEndingWith("Service").and(resideInAPackage(ROOT + "..")))
                .should(returnOnlyIdsRecordsOrEnums())
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void publicServicesDoNotTakeEntitiesAsParameters() {
        methods().that().arePublic().and().areDeclaredInClassesThat(
                        simpleNameEndingWith("Service").and(resideInAPackage(ROOT + "..")))
                .should(new ArchCondition<JavaMethod>("not take JPA entities as parameters") {
                    @Override
                    public void check(JavaMethod m, ConditionEvents events) {
                        m.getParameterTypes().stream()
                                .flatMap(t -> t.getAllInvolvedRawTypes().stream())
                                .filter(ArchitectureTest::isEntity)
                                .forEach(t -> events.add(SimpleConditionEvent.violated(m,
                                        m.getFullName() + " takes entity " + t.getName())));
                    }
                })
                .allowEmptyShould(true)
                .check(classes);
    }

    // ---- organization_id never grants access ---------------------------------------------------

    @Test
    void organizationIdIsOnlyMappedInCoachEntity() {
        fields().that().haveNameMatching("(?i)organization_?id")
                .should().beDeclaredInClassesThat().haveSimpleName("Coach")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noRepositoryQueryMentionsOrganization() {
        methods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Repository")
                .should(new ArchCondition<JavaMethod>("not mention organization in name or @Query") {
                    @Override
                    public void check(JavaMethod m, ConditionEvents events) {
                        boolean inName = m.getName().toLowerCase().contains("organization");
                        boolean inQuery = m.getAnnotations().stream()
                                .filter(a -> a.getRawType().getName().endsWith(".Query"))
                                .anyMatch(a -> String.valueOf(a.get("value").orElse("")).toLowerCase().contains("organization"));
                        if (inName || inQuery) {
                            events.add(SimpleConditionEvent.violated(m, m.getFullName() + " uses organization"));
                        }
                    }
                })
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void noClassOutsideCoachAccessesOrganizationId() {
        noClasses().that().haveSimpleNameNotEndingWith("Coach")
                .should().accessFieldWhere(
                        com.tngtech.archunit.core.domain.JavaFieldAccess.Predicates.target(
                                com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching("(?i)organization_?id")))
                .allowEmptyShould(true)
                .check(classes);
    }

    // ---- domain purity, stricter -----------------------------------------------------------------

    @Test
    void domainOnlyDependsOnTheJdkItselfAndApiTypes() {
        classes().that().resideInAPackage("..domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", ROOT + "..domain..", ROOT + "..api..")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void domainNeverReadsTheSystemClockDirectly() {
        noClasses().that().resideInAPackage("..domain..")
                .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("call a java.time now() that does not take a Clock") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        return call.getTarget().getName().equals("now")
                                && call.getTarget().getOwner().getPackageName().equals("java.time")
                                && call.getTarget().getRawParameterTypes().stream().noneMatch(t -> t.getName().equals("java.time.Clock"));
                    }
                })
                .because("domain classes receive an injected java.time.Clock so they can be tested")
                .allowEmptyShould(true)
                .check(classes);
    }

    // ---- layering ---------------------------------------------------------------------------------

    @Test
    void controllersDoNotTouchRepositories() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .allowEmptyShould(true)
                .check(classes);
    }

    // ---- cross-tenant access is confined -----------------------------------------------------------

    @Test
    void crossTenantComponentsAreNotPublic() {
        classes().that().areAnnotatedWith(CrossTenantAccess.class)
                .should().notBePublic()
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void crossTenantComponentsAreNeverUsedByControllers() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().areAnnotatedWith(CrossTenantAccess.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void crossTenantComponentsAreOnlyUsedByServicesAndJobs() {
        classes().that().areAnnotatedWith(CrossTenantAccess.class)
                .should().onlyHaveDependentClassesThat(
                        simpleNameEndingWith("Service").or(simpleNameEndingWith("Job"))
                                .or(DescribedPredicate.describe("the component itself", (JavaClass c) ->
                                        c.isAnnotatedWith(CrossTenantAccess.class)
                                                || c.getEnclosingClass().map(e -> e.isAnnotatedWith(CrossTenantAccess.class)).orElse(false))))
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void onlyCrossTenantComponentsMayTouchJdbc() {
        noClasses().that().areNotAnnotatedWith(CrossTenantAccess.class)
                .and(DescribedPredicate.describe("are not nested in a cross-tenant component", (JavaClass c) ->
                        !c.getEnclosingClass().map(e -> e.isAnnotatedWith(CrossTenantAccess.class)).orElse(false)))
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..", "java.sql..")
                .because("plain JDBC bypasses the Hibernate tenant filter")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void repositoriesNeverUseNativeQueries() {
        methods().that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Repository")
                .should(new ArchCondition<JavaMethod>("not use @Query(nativeQuery = true), which bypasses the tenant filter") {
                    @Override
                    public void check(JavaMethod m, ConditionEvents events) {
                        m.getAnnotations().stream()
                                .filter(a -> a.getRawType().getName().endsWith(".Query"))
                                .filter(a -> Boolean.TRUE.equals(a.get("nativeQuery").orElse(false)))
                                .forEach(a -> events.add(SimpleConditionEvent.violated(m, m.getFullName() + " is a native query")));
                    }
                })
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void everyBusinessEntityIsTenantScoped() {
        classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                .and().resideInAnyPackage(pkg("students"), pkg("billing"), pkg("scheduling"), pkg("notifications"))
                .should().beAssignableTo(com.coachplatform.tenant.TenantScopedEntity.class)
                .allowEmptyShould(true)
                .check(classes);
    }

    // ---- the single approved internal port -------------------------------------------------------

    @Test
    void cycleSessionsIsTheOnlyInterfaceInAnyApiPackage() {
        classes().that().areInterfaces().and().resideInAPackage("..api..")
                .should().haveSimpleName("CycleSessions")
                .because("the only approved internal port is billing.api.CycleSessions; other module boundaries use *Service classes")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void cycleSessionsIsImplementedOnlyByScheduling() {
        classes().that().implement(com.coachplatform.billing.api.CycleSessions.class)
                .should().resideInAPackage(pkg("scheduling"))
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    void nothingOutsideTheApiPackagesIsImplementedAcrossModules() {
        // A class may implement an interface of another module only if that interface is in an ..api.. package
        // (and the previous rules narrow that to CycleSessions). Repositories/Spring interfaces are not module code.
        for (String module : MODULES) {
            for (String other : MODULES) {
                if (module.equals(other)) {
                    continue;
                }
                noClasses().that().resideInAPackage(pkg(module))
                        .should().implement(DescribedPredicate.describe("an interface of module '" + other + "' outside its api package",
                                (JavaClass c) -> c.isInterface() && c.getPackageName().startsWith(ROOT + "." + other)
                                        && !c.getPackageName().contains(".api")
                                        && !c.getPackageName().contains(".domain")))
                        .allowEmptyShould(true)
                        .check(classes);
            }
        }
    }

    // ---- sanity: the rules above are not vacuous ------------------------------------------------

    @Test
    void importedClassesIncludeTheProductionModules() {
        assertThat(classes.contain(com.coachplatform.coach.CoachService.class)).isTrue();
        assertThat(classes.contain(com.coachplatform.auth.AuthService.class)).isTrue();
        assertThat(classes.contain(com.coachplatform.billing.BillingService.class)).isTrue();
        assertThat(classes.contain(com.coachplatform.students.StudentService.class)).isTrue();
        assertThat(classes.contain(com.coachplatform.scheduling.SchedulingService.class)).isTrue();
        assertThat(classes.contain(com.coachplatform.billing.api.CycleSessions.class)).isTrue();
        assertThat(classes.stream().filter(c -> c.isAnnotatedWith(CrossTenantAccess.class)).count()).isEqualTo(2);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String pkg(String module) {
        return ROOT + "." + module + "..";
    }

    private static boolean isEntity(JavaClass c) {
        return c.isAnnotatedWith("jakarta.persistence.Entity")
                || c.isAnnotatedWith("jakarta.persistence.MappedSuperclass")
                || c.isAssignableTo(com.coachplatform.tenant.TenantScopedEntity.class);
    }

    private static ArchCondition<JavaMethod> returnOnlyIdsRecordsOrEnums() {
        return new ArchCondition<>("return only primitives, java.* types, records or enums (never entities)") {
            @Override
            public void check(JavaMethod m, ConditionEvents events) {
                for (JavaClass t : m.getReturnType().getAllInvolvedRawTypes()) {
                    boolean ok = t.isPrimitive()
                            || t.getName().startsWith("java.")
                            || t.isEnum()
                            || t.isRecord();
                    if (!ok || isEntity(t)) {
                        events.add(SimpleConditionEvent.violated(m,
                                m.getFullName() + " returns " + t.getName() + " (only IDs, records/DTOs or enums allowed)"));
                    }
                }
            }
        };
    }
}
