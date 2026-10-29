package com.salonplatform.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;

/**
 * The persistence convention of {@code docs/engineering/backend.md} ("Persistence and migrations", "Code
 * generation"), written so that it can be applied to any base package: to the application, and to the
 * fixtures that prove each rule fails on a violation. Every entity keeps its fields to itself and cannot be
 * given another id, salon, version or creation time; the {@code EntityManager}, {@code JdbcClient} and the
 * Spring Data repositories belong to a module's {@code infrastructure} or to {@code shared}; and no code uses
 * {@code JdbcTemplate}.
 */
final class PersistenceRules {

    private static final String ENTITY = "jakarta.persistence.Entity";

    private static final String ENTITY_MANAGER = "jakarta.persistence.EntityManager";

    private static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";

    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";

    private static final Set<String> JDBC_TEMPLATES = Set.of(
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate");

    private static final Set<String> KEY_SETTERS = Set.of("setId", "setTenantId", "setVersion", "setCreatedAt");

    private final String base;

    PersistenceRules(String base) {
        this.base = base;
    }

    List<ArchRule> all() {
        return List.of(
                entitiesKeepTheirFieldsToThemselves(),
                entitiesHaveNoKeySetter(),
                persistenceIsReachedOnlyFromInfrastructureOrShared(),
                repositoriesAreDeclaredOnlyInInfrastructureOrShared(),
                nothingUsesJdbcTemplate());
    }

    /** An entity changes only through the methods named for what they do. */
    ArchRule entitiesKeepTheirFieldsToThemselves() {
        return classes()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .areAnnotatedWith(ENTITY)
                .should(haveNoPublicField())
                .as("no @Entity has a public field")
                .allowEmptyShould(true);
    }

    /** An entity's id, salon, version and creation time are set when it is created, and never again. */
    ArchRule entitiesHaveNoKeySetter() {
        return classes()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .areAnnotatedWith(ENTITY)
                .should(haveNoMethodNamedOneOf(KEY_SETTERS))
                .as("no @Entity has a method setId, setTenantId, setVersion or setCreatedAt")
                .allowEmptyShould(true);
    }

    /**
     * Use cases reach their data through repositories; direct SQL is one of the named exceptions and lives in
     * {@code infrastructure} (or {@code shared}), so that each is found, states its tenant predicate and has
     * its row-level security test.
     */
    ArchRule persistenceIsReachedOnlyFromInfrastructureOrShared() {
        return noClasses()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .resideOutsideOfPackages(infrastructureOrShared())
                .should()
                .dependOnClassesThat(entityManagerOrJdbcClient())
                .as("the EntityManager and JdbcClient are used only in infrastructure packages and shared")
                .allowEmptyShould(true);
    }

    ArchRule repositoriesAreDeclaredOnlyInInfrastructureOrShared() {
        return classes()
                .that()
                .resideInAPackage(base + "..")
                .and()
                .areInterfaces()
                .and()
                .areAssignableTo(SPRING_DATA_REPOSITORY)
                .should()
                .resideInAnyPackage(infrastructureOrShared())
                .as("Spring Data repositories are declared only in infrastructure packages and shared")
                .allowEmptyShould(true);
    }

    ArchRule nothingUsesJdbcTemplate() {
        return noClasses()
                .that()
                .resideInAPackage(base + "..")
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                        "are JdbcTemplate", type -> JDBC_TEMPLATES.contains(type.getName())))
                .as("nothing uses JdbcTemplate")
                .allowEmptyShould(true);
    }

    private String[] infrastructureOrShared() {
        return new String[] {base + ".*.infrastructure..", base + ".shared.."};
    }

    /** The {@code EntityManager}, and {@code JdbcClient} with the statement types nested in it. */
    private static DescribedPredicate<JavaClass> entityManagerOrJdbcClient() {
        return DescribedPredicate.describe(
                "are the EntityManager or JdbcClient",
                type -> type.getName().equals(ENTITY_MANAGER)
                        || type.getName().equals(JDBC_CLIENT)
                        || type.getName().startsWith(JDBC_CLIENT + "$"));
    }

    private static ArchCondition<JavaClass> haveNoPublicField() {
        return new ArchCondition<>("have no public field") {
            @Override
            public void check(JavaClass entity, ConditionEvents events) {
                for (JavaField field : entity.getFields()) {
                    if (field.getModifiers().contains(JavaModifier.PUBLIC)) {
                        events.add(SimpleConditionEvent.violated(
                                entity, entity.getName() + " has the public field " + field.getName()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> haveNoMethodNamedOneOf(Set<String> names) {
        return new ArchCondition<>("have no method named " + String.join(", ", names)) {
            @Override
            public void check(JavaClass entity, ConditionEvents events) {
                for (JavaMethod method : entity.getMethods()) {
                    if (names.contains(method.getName())) {
                        events.add(SimpleConditionEvent.violated(
                                entity, entity.getName() + " has the method " + method.getName()));
                    }
                }
            }
        };
    }
}
