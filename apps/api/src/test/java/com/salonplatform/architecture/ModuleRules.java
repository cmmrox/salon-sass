package com.salonplatform.architecture;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The rules between modules, from {@code docs/engineering/backend.md}, written so that they can be
 * applied to any base package: to the application, and to the fixture that proves each rule
 * really fails on a violation.
 */
final class ModuleRules {

    static final List<String> MODULES = List.of("platform", "shared", "identity", "catalogue", "staff", "booking",
            "payment", "notification", "customer", "reporting", "branding");

    private static final String SHARED = "shared";

    private static final String REPORTING = "reporting";

    private final String base;

    ModuleRules(String base) {
        this.base = base;
    }

    List<ArchRule> all() {
        return List.of(everyClassBelongsToAKnownModule(), sharedDependsOnNoOtherModule(), nothingDependsOnReporting(),
                modulesAreFreeOfCycles(), anotherModuleIsUsedOnlyThroughItsRootPackage(), domainUsesNoSpringAndNoHttp(),
                theLocalHooksAreUsedByNothingElse(), theLocalHooksLoadInTheLocalProfileOnly());
    }

    /** A package beside the modules would escape every other rule here. */
    ArchRule everyClassBelongsToAKnownModule() {
        List<String> allowed = new ArrayList<>();
        allowed.add(base);
        MODULES.forEach(module -> allowed.add(everythingIn(module)));
        return classes().that().resideInAPackage(base + "..")
                .should().resideInAnyPackage(allowed.toArray(String[]::new))
                .as("every class belongs to a module listed in docs/engineering/backend.md")
                .allowEmptyShould(true);
    }

    ArchRule sharedDependsOnNoOtherModule() {
        String[] otherModules = MODULES.stream().filter(module -> !module.equals(SHARED)).map(this::everythingIn)
                .toArray(String[]::new);
        return noClasses().that().resideInAPackage(everythingIn(SHARED))
                .should().dependOnClassesThat().resideInAnyPackage(otherModules)
                .as("shared depends on no other module")
                .allowEmptyShould(true);
    }

    ArchRule nothingDependsOnReporting() {
        return noClasses().that().resideInAPackage(base + "..").and().resideOutsideOfPackage(everythingIn(REPORTING))
                .should().dependOnClassesThat().resideInAPackage(everythingIn(REPORTING))
                .as("nothing depends on reporting")
                .allowEmptyShould(true);
    }

    ArchRule modulesAreFreeOfCycles() {
        return slices().matching(base + ".(*)..").should().beFreeOfCycles()
                .as("dependencies between modules point one way")
                .allowEmptyShould(true);
    }

    /**
     * Another module is used through the types in its root package (its facade and its events),
     * never through its controllers, use cases, entities or repositories. {@code shared} is the
     * exception: it exists to be used.
     */
    ArchRule anotherModuleIsUsedOnlyThroughItsRootPackage() {
        return noClasses().that().resideInAPackage(base + "..")
                .should(reachIntoAnotherModule())
                .as("another module is used only through the types in its root package")
                .allowEmptyShould(true);
    }

    ArchRule domainUsesNoSpringAndNoHttp() {
        return noClasses().that().resideInAPackage(base + ".*.domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.servlet..")
                .as("domain code uses no Spring and no HTTP")
                .allowEmptyShould(true);
    }

    /**
     * The local test hooks ({@code shared.local}: the test clock, the probe and its fake provider)
     * are used by nothing outside their own package, so that no other code can come to depend on
     * something that exists on a developer's machine only.
     */
    ArchRule theLocalHooksAreUsedByNothingElse() {
        return noClasses().that().resideInAPackage(base + "..").and().resideOutsideOfPackage(localHooks())
                .should().dependOnClassesThat().resideInAPackage(localHooks())
                .as("the local test hooks are used by nothing outside shared.local")
                .allowEmptyShould(true);
    }

    /**
     * Everything Spring creates in {@code shared.local} (a controller, a service, a component, a
     * configuration) carries {@code @Profile("local")} and no other profile, so that none of it
     * exists in any other environment.
     */
    ArchRule theLocalHooksLoadInTheLocalProfileOnly() {
        return classes().that().resideInAPackage(localHooks()).and().areMetaAnnotatedWith(SPRING_COMPONENT)
                .should(loadInTheLocalProfileOnly())
                .as("every Spring bean of the local test hooks loads in the local profile only")
                .allowEmptyShould(true);
    }

    private static final String SPRING_COMPONENT = "org.springframework.stereotype.Component";

    private static final String SPRING_PROFILE = "org.springframework.context.annotation.Profile";

    private String localHooks() {
        return base + "." + SHARED + ".local..";
    }

    private static ArchCondition<JavaClass> loadInTheLocalProfileOnly() {
        return new ArchCondition<>("carry @Profile(\"local\") and no other profile") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean localOnly = javaClass.getAnnotations().stream()
                        .filter(annotation -> annotation.getRawType().getName().equals(SPRING_PROFILE))
                        .map(JavaAnnotation::getProperties)
                        .anyMatch(properties -> properties.get("value") instanceof String[] profiles
                                && List.of(profiles).equals(List.of("local")));
                if (!localOnly) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getName() + " is a Spring bean of the local test hooks without @Profile(\"local\")"));
                }
            }
        };
    }

    private ArchCondition<JavaClass> reachIntoAnotherModule() {
        return new ArchCondition<>("reach into another module") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = moduleOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String to = moduleOf(target);
                    boolean anotherModule = to != null && !to.equals(from) && !to.equals(SHARED);
                    if (anotherModule && !target.getPackageName().equals(base + "." + to)) {
                        events.add(SimpleConditionEvent.satisfied(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** The module a class is in, or null for a class outside every module. */
    private String moduleOf(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        for (String module : MODULES) {
            String root = base + "." + module;
            if (packageName.equals(root) || packageName.startsWith(root + ".")) {
                return module;
            }
        }
        return null;
    }

    private String everythingIn(String module) {
        return base + "." + module + "..";
    }
}
