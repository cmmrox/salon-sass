package com.salonplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The rules between modules and the layout inside one, from {@code docs/engineering/backend.md} ("Modules",
 * "Inside a module"), that Spring Modulith's verification does not cover, written so that they can be applied
 * to any base package: to the application, and to the fixture that proves each rule really fails on a
 * violation. Modulith verifies that dependencies between
 * modules point one way, that another module is used only through its root package and its named
 * interfaces, and that a module uses only what its {@code allowedDependencies} declare.
 */
final class ModuleRules {

    static final List<String> MODULES = List.of(
            "platform",
            "shared",
            "identity",
            "catalogue",
            "staff",
            "booking",
            "payment",
            "notification",
            "customer",
            "reporting",
            "branding");

    private static final String SHARED = "shared";

    private static final String REPORTING = "reporting";

    /** The folders a business module may have (CR-022); {@code shared} is organised by capability instead. */
    static final Set<String> LAYOUT = Set.of(
            "controller",
            "dto",
            "mapper",
            "service",
            "entity",
            "domain",
            "repository",
            "adapter",
            "job",
            "config",
            "event",
            "inprocess",
            "local");

    /** The one {@code Impl} name: Spring Data finds a repository fragment's implementation by that suffix. */
    private static final String REPOSITORY_FRAGMENT = "NewEntitiesImpl";

    private final String base;

    ModuleRules(String base) {
        this.base = base;
    }

    List<ArchRule> all() {
        return List.of(
                everyClassBelongsToAKnownModule(),
                sharedDependsOnNoOtherModule(),
                nothingDependsOnReporting(),
                domainUsesNoSpringJpaOrHttp(),
                theLocalHooksAreUsedByNothingElse(),
                theLocalHooksLoadInTheLocalProfileOnly(),
                rootPackagesHoldOnlyContracts(),
                providedTypesCarryNoOpenApiAnnotation(),
                everyMapperNamesTheSharedConfiguration(),
                businessModulesUseOnlyTheLayoutFolders(),
                mappersLiveInMapper(),
                controllersLiveInControllerOrLocal(),
                requestAndResponseRecordsLiveInDto(),
                noClassIsNamedImpl());
    }

    /** A package beside the modules would escape every other rule here. */
    ArchRule everyClassBelongsToAKnownModule() {
        List<String> allowed = new ArrayList<>();
        allowed.add(base);
        MODULES.forEach(module -> allowed.add(everythingIn(module)));
        return classes()
                .that()
                .resideInAPackage(base + "..")
                .should()
                .resideInAnyPackage(allowed.toArray(String[]::new))
                .as("every class belongs to a module listed in docs/engineering/backend.md")
                .allowEmptyShould(true);
    }

    ArchRule sharedDependsOnNoOtherModule() {
        String[] otherModules = MODULES.stream()
                .filter(module -> !module.equals(SHARED))
                .map(this::everythingIn)
                .toArray(String[]::new);
        return noClasses()
                .that()
                .resideInAPackage(everythingIn(SHARED))
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(otherModules)
                .as("shared depends on no other module")
                .allowEmptyShould(true);
    }

    ArchRule nothingDependsOnReporting() {
        return noClasses()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .resideOutsideOfPackage(everythingIn(REPORTING))
                .should()
                .dependOnClassesThat()
                .resideInAPackage(everythingIn(REPORTING))
                .as("nothing depends on reporting")
                .allowEmptyShould(true);
    }

    /** Pure business rules and value objects; entities with their mapping live in {@code entity}. */
    ArchRule domainUsesNoSpringJpaOrHttp() {
        return noClasses()
                .that()
                .resideInAPackage(base + ".*.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "jakarta.servlet..")
                .as("domain code uses no Spring, no JPA and no HTTP")
                .allowEmptyShould(true);
    }

    /**
     * The local test hooks ({@code shared.local}: the test clock, the probe and its fake provider)
     * are used by nothing outside their own package, so that no other code can come to depend on
     * something that exists on a developer's machine only.
     */
    ArchRule theLocalHooksAreUsedByNothingElse() {
        return noClasses()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .resideOutsideOfPackage(localHooks())
                .should()
                .dependOnClassesThat()
                .resideInAPackage(localHooks())
                .as("the local test hooks are used by nothing outside shared.local")
                .allowEmptyShould(true);
    }

    /**
     * Everything Spring creates in {@code shared.local} (a controller, a service, a component, a
     * configuration) carries {@code @Profile("local")} and no other profile, so that none of it
     * exists in any other environment.
     */
    ArchRule theLocalHooksLoadInTheLocalProfileOnly() {
        return classes()
                .that()
                .resideInAPackage(localHooks())
                .and()
                .areMetaAnnotatedWith(SPRING_COMPONENT)
                .should(loadInTheLocalProfileOnly())
                .as("every Spring bean of the local test hooks loads in the local profile only")
                .allowEmptyShould(true);
    }

    /**
     * A module's root package is its public surface: provided interfaces, the records they take and
     * return, enums, exceptions and events. A class with behaviour, or a Spring bean, belongs in
     * {@code service/}, behind the interface it implements.
     */
    ArchRule rootPackagesHoldOnlyContracts() {
        return classes()
                .that()
                .resideInAnyPackage(rootPackages())
                .should(beAContract())
                .as("a module's root package holds only interfaces, records, enums and exceptions")
                .allowEmptyShould(true);
    }

    /**
     * A provided type is not a REST response shape: a controller that returns the same data has its own
     * response record in {@code dto/}, and only that carries OpenAPI annotations.
     */
    ArchRule providedTypesCarryNoOpenApiAnnotation() {
        List<String> provided = new ArrayList<>(List.of(rootPackages()));
        provided.add(base + ".*." + IN_PROCESS + "..");
        return noClasses()
                .that()
                .resideInAnyPackage(provided.toArray(String[]::new))
                .should()
                .dependOnClassesThat()
                .resideInAPackage("io.swagger..")
                .as("no type of a root package or an inprocess package carries an OpenAPI annotation")
                .allowEmptyShould(true);
    }

    /**
     * Every MapStruct mapper names {@code ResponseMapperConfig}, which makes an unmapped response property
     * and a lossy conversion build errors and the mapper a Spring bean (decision 056).
     */
    ArchRule everyMapperNamesTheSharedConfiguration() {
        return classes()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .areAnnotatedWith(MAPSTRUCT_MAPPER)
                .should(nameTheSharedMapperConfiguration())
                .as("every MapStruct mapper names ResponseMapperConfig")
                .allowEmptyShould(true);
    }

    /**
     * Inside a business module, every class is in its root package or in one of the folders of
     * {@link #LAYOUT}, so that a reader finds each kind of class where the layout says it is.
     */
    ArchRule businessModulesUseOnlyTheLayoutFolders() {
        return classes()
                .that()
                .resideInAnyPackage(businessModules())
                .should(beInTheRootOrALayoutFolder())
                .as("a business module's classes are in its root package or in a folder of the layout")
                .allowEmptyShould(true);
    }

    ArchRule mappersLiveInMapper() {
        return classes()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .areAnnotatedWith(MAPSTRUCT_MAPPER)
                .should()
                .resideInAPackage(base + ".*.mapper..")
                .as("MapStruct mappers live in mapper packages")
                .allowEmptyShould(true);
    }

    /** {@code shared} serves its own endpoints from its capability packages ({@code web}, {@code local}). */
    ArchRule controllersLiveInControllerOrLocal() {
        return classes()
                .that()
                .resideInAnyPackage(businessModules())
                .and()
                .areMetaAnnotatedWith(SPRING_CONTROLLER)
                .should()
                .resideInAnyPackage(base + ".*.controller..", base + ".*.local..")
                .as("a business module's controllers live in controller, or in local for the test hooks")
                .allowEmptyShould(true);
    }

    /**
     * A top-level record that carries an OpenAPI annotation is a request or response shape, and lives in
     * {@code dto}. A provided record in the root package carries none ({@link #providedTypesCarryNoOpenApiAnnotation}).
     */
    ArchRule requestAndResponseRecordsLiveInDto() {
        return classes()
                .that()
                .resideInAnyPackage(businessModules())
                .and(DescribedPredicate.describe(
                        "are top-level records with an OpenAPI annotation",
                        type -> type.isRecord() && type.isTopLevelClass() && carriesAnOpenApiAnnotation(type)))
                .should()
                .resideInAPackage(base + ".*.dto..")
                .as("a business module's request and response records live in dto")
                .allowEmptyShould(true);
    }

    /**
     * An implementation is named for what it is ({@code docs/engineering/backend.md}, "Clean code"). The
     * exceptions are the Spring Data fragment and the classes MapStruct generates for a mapper.
     */
    ArchRule noClassIsNamedImpl() {
        return noClasses()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .haveSimpleNameNotEndingWith(REPOSITORY_FRAGMENT)
                .and(DescribedPredicate.not(implementAMapper()))
                .should()
                .haveSimpleNameEndingWith("Impl")
                .as("no class is named ...Impl except NewEntitiesImpl and generated mappers")
                .allowEmptyShould(true);
    }

    private static final String SPRING_COMPONENT = "org.springframework.stereotype.Component";

    private static final String SPRING_CONTROLLER = "org.springframework.stereotype.Controller";

    private static final String SPRING_PROFILE = "org.springframework.context.annotation.Profile";

    private static final String MAPSTRUCT_MAPPER = "org.mapstruct.Mapper";

    private static final String RESPONSE_MAPPER_CONFIG = "com.salonplatform.shared.mapping.ResponseMapperConfig";

    private static final String IN_PROCESS = "inprocess";

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
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName()
                                    + " is a Spring bean of the local test hooks without @Profile(\"local\")"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> beAContract() {
        return new ArchCondition<>("be an interface, a record, an enum or an exception, and no Spring bean") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean contract = javaClass.isInterface()
                        || javaClass.isRecord()
                        || javaClass.isEnum()
                        || javaClass.isAssignableTo(Throwable.class);
                if (!contract || javaClass.isMetaAnnotatedWith(SPRING_COMPONENT)) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName()
                                    + " is in a module's root package but is not an interface, a record, an enum"
                                    + " or an exception, or is a Spring bean"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> nameTheSharedMapperConfiguration() {
        return new ArchCondition<>("name ResponseMapperConfig as its config") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean configured = javaClass
                        .getAnnotationOfType(MAPSTRUCT_MAPPER)
                        .get("config")
                        .filter(config -> config instanceof JavaClass type
                                && type.getName().equals(RESPONSE_MAPPER_CONFIG))
                        .isPresent();
                if (!configured) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName() + " is a MapStruct mapper that does not name ResponseMapperConfig"));
                }
            }
        };
    }

    private ArchCondition<JavaClass> beInTheRootOrALayoutFolder() {
        return new ArchCondition<>("be in the module's root package or in a folder of the layout") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                String[] segments =
                        javaClass.getPackageName().substring(base.length() + 1).split("\\.");
                if (segments.length > 1 && !LAYOUT.contains(segments[1])) {
                    events.add(SimpleConditionEvent.violated(
                            javaClass,
                            javaClass.getName() + " is in the folder '" + segments[1]
                                    + "', which is not one of the layout's folders " + LAYOUT));
                }
            }
        };
    }

    private static boolean carriesAnOpenApiAnnotation(JavaClass type) {
        return type.getDirectDependenciesFromSelf().stream()
                .anyMatch(dependency ->
                        dependency.getTargetClass().getPackageName().startsWith("io.swagger."));
    }

    private static DescribedPredicate<JavaClass> implementAMapper() {
        return DescribedPredicate.describe(
                "implement a MapStruct mapper",
                type -> Stream.concat(type.getAllRawInterfaces().stream(), type.getAllRawSuperclasses().stream())
                        .anyMatch(mapper -> mapper.isAnnotatedWith(MAPSTRUCT_MAPPER)));
    }

    /** Every module but {@code shared}, with its sub-packages. */
    private String[] businessModules() {
        return MODULES.stream()
                .filter(module -> !module.equals(SHARED))
                .map(this::everythingIn)
                .toArray(String[]::new);
    }

    /** Each module's root package, exactly: not its sub-packages. */
    private String[] rootPackages() {
        return MODULES.stream().map(module -> base + "." + module).toArray(String[]::new);
    }

    private String everythingIn(String module) {
        return base + "." + module + "..";
    }
}
