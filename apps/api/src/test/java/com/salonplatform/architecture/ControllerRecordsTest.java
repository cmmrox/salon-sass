package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.salonplatform.architecture.FixtureCompiler.Compilation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The rule that the records a controller reads or returns are top-level records in its module's dto
 * ({@link ModuleRules#controllerRecordsAreTopLevelInDto()}), shown trees that keep and break it. The fixtures
 * are controllers, which Spring would scan from the test class path and serve, so they are sources under
 * {@code src/test/compile-fixtures/fixture/shapes}, compiled here into a directory of their own.
 */
class ControllerRecordsTest {

    private static final List<String> CLASS_PATH = List.of(
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.http.ResponseEntity",
            "org.springframework.stereotype.Controller",
            "org.springframework.util.MultiValueMap",
            "org.jspecify.annotations.Nullable");

    @TempDir
    Path output;

    @Test
    void aControllerThatUsesOnlyItsModulesDtoPasses() {
        rule("clean").check(fixture("clean"));
    }

    /** Each tree breaks the rule once, and the rule names the record that breaks it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void aRecordOutsideTheControllersDtoIsCaught(String fixture, String offender) {
        EvaluationResult result = rule(fixture).evaluate(fixture(fixture));

        assertThat(result.hasViolation())
                .as("the rule fails on fixture '%s'", fixture)
                .isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains(offender);
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                arguments("nestedrequest", "booking.controller.BookingController$BookRequest"),
                arguments("nestedresponse", "booking.service.BookBooking$Slot"),
                arguments("nestedcomponent", "booking.service.BookBooking$Slot"),
                arguments("othermodule", "payment.dto.Receipt"));
    }

    private static ArchRule rule(String fixture) {
        return new ModuleRules("fixture.shapes." + fixture).controllerRecordsAreTopLevelInDto();
    }

    private JavaClasses fixture(String name) {
        Path classes = output.resolve(name);
        Compilation compilation = FixtureCompiler.compile(
                List.of("-proc:none", "-classpath", FixtureCompiler.pathOf(CLASS_PATH), "-d", classes.toString()),
                sources(FixtureCompiler.FIXTURES.resolve("shapes").resolve(name)));
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
