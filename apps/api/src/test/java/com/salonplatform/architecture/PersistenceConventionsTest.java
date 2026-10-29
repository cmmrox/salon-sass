package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.salonplatform.SalonApiApplication;
import com.salonplatform.architecture.FixtureCompiler.Compilation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fails the build on a break of the persistence convention ({@link PersistenceRules}). The fixtures are
 * entities, repositories and SQL that Spring would pick up from the test class path, so they are sources under
 * {@code src/test/compile-fixtures/fixture/persistence}, compiled here into a directory of their own.
 */
class PersistenceConventionsTest {

    private static final String BASE = SalonApiApplication.class.getPackageName();

    private static final List<String> CLASS_PATH = List.of(
            "jakarta.persistence.Entity",
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.data.jpa.repository.JpaRepository",
            "org.springframework.data.repository.Repository",
            "org.springframework.beans.factory.InitializingBean",
            "org.springframework.core.NestedRuntimeException",
            "org.springframework.dao.DataAccessException",
            "org.springframework.transaction.TransactionDefinition");

    @TempDir
    Path output;

    @Test
    void theApplicationKeepsThePersistenceConvention() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
        assertThat(application.contain(BASE + ".identity.entity.StaffAccount"))
                .as("the application's entities were found")
                .isTrue();

        new PersistenceRules(BASE).all().forEach(rule -> rule.check(application));
    }

    @Test
    void aTreeThatFollowsTheConventionPasses() {
        JavaClasses clean = fixture("clean");

        new PersistenceRules(base("clean")).all().forEach(rule -> rule.check(clean));
    }

    /** Each rule, shown a tree that breaks it, names the class that breaks it and how. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void aBreakOfTheConventionIsCaught(
            String fixture, Function<PersistenceRules, ArchRule> rule, String offender, String how) {
        EvaluationResult result =
                rule.apply(new PersistenceRules(base(fixture))).evaluate(fixture(fixture));

        assertThat(result.hasViolation())
                .as("the rule fails on fixture '%s'", fixture)
                .isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails()))
                .contains(offender)
                .contains(how);
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                arguments(
                        "publicfield",
                        rule(PersistenceRules::entitiesKeepTheirFieldsToThemselves),
                        "entity.Booking",
                        "public field status"),
                arguments(
                        "keysetter",
                        rule(PersistenceRules::entitiesHaveNoKeySetter),
                        "entity.Booking",
                        "method setTenantId"),
                arguments(
                        "entitymanager",
                        rule(PersistenceRules::persistenceIsReachedOnlyFromRepositoryOrShared),
                        "service.BookBooking",
                        "jakarta.persistence.EntityManager"),
                arguments(
                        "jdbcclient",
                        rule(PersistenceRules::persistenceIsReachedOnlyFromRepositoryOrShared),
                        "service.BookBooking",
                        "org.springframework.jdbc.core.simple.JdbcClient"),
                arguments(
                        "repository",
                        rule(PersistenceRules::repositoriesAreDeclaredOnlyInRepositoryOrShared),
                        "service.BookingRepository",
                        "repository"),
                arguments(
                        "jdbctemplate",
                        rule(PersistenceRules::nothingUsesJdbcTemplate),
                        "repository.BookingRows",
                        "org.springframework.jdbc.core.JdbcTemplate"));
    }

    private static Function<PersistenceRules, ArchRule> rule(Function<PersistenceRules, ArchRule> rule) {
        return rule;
    }

    private static String base(String fixture) {
        return "fixture.persistence." + fixture;
    }

    private JavaClasses fixture(String name) {
        Path classes = output.resolve(name);
        Compilation compilation = FixtureCompiler.compile(
                List.of("-proc:none", "-classpath", FixtureCompiler.pathOf(CLASS_PATH), "-d", classes.toString()),
                sources(FixtureCompiler.FIXTURES.resolve("persistence").resolve(name)));
        assertThat(compilation.errors()).as("fixture '%s' compiles", name).isEmpty();
        assertThat(compilation.succeeded()).isTrue();
        JavaClasses imported = new ClassFileImporter().importPath(classes);
        assertThat(imported).as("fixture '%s' has classes", name).isNotEmpty();
        return imported;
    }

    private static List<Path> sources(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
