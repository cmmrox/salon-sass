package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.salonplatform.SalonApiApplication;
import com.salonplatform.architecture.CodeOwnership.TableReference;
import com.salonplatform.architecture.FixtureCompiler.Compilation;
import com.salonplatform.shared.infrastructure.ModuleOwnership;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A module's code names only its own tables and reads only its own settings and {@code shared}'s
 * ({@link CodeOwnership}), held against the ownership map. The fixtures are entities and Spring-annotated
 * classes that must stay off the test class path, so they are sources under
 * {@code src/test/compile-fixtures/fixture/ownership}, compiled here into a directory of their own.
 */
class CodeOwnershipTest {

    private static final String BASE = SalonApiApplication.class.getPackageName();

    private static final ModuleOwnership OWNERSHIP = ModuleOwnership.load();

    private static final List<String> CLASS_PATH = List.of(
            "jakarta.persistence.Entity",
            "org.springframework.beans.factory.annotation.Value",
            "org.springframework.core.env.Environment");

    @TempDir
    Path output;

    @Test
    void theApplicationNamesOnlyItsOwnTables() {
        List<CompiledStrings> application = application();
        List<TableReference> references = CodeOwnership.tableReferences(OWNERSHIP, application);

        assertThat(references)
                .as("the scan sees @Table, SQL and JPQL")
                .contains(
                        new TableReference(
                                BASE + ".identity.domain.StaffAccount", "identity", "identity_staff", "identity"),
                        new TableReference(
                                BASE + ".platform.infrastructure.SalonStore", "platform", "tenant", "platform"),
                        new TableReference(
                                BASE + ".identity.infrastructure.StaffAccountRepository",
                                "identity",
                                "StaffAccount",
                                "identity"));
        assertThat(CodeOwnership.foreignTables(OWNERSHIP, application)).isEmpty();
    }

    @Test
    void theApplicationReadsOnlyItsOwnSettingsAndShareds() {
        List<CompiledStrings> application = application();

        assertThat(application.stream().flatMap(type -> type.all().stream()))
                .as("the scan sees placeholders and joined names")
                .contains("${salon.platform-domain}", "salon.operator.\u0001-key");
        assertThat(CodeOwnership.foreignSettings(OWNERSHIP, application)).isEmpty();
    }

    @Test
    void aTreeThatKeepsTheRulesPasses() {
        List<CompiledStrings> clean = fixture("clean");

        assertThat(CodeOwnership.tableReferences(OWNERSHIP, clean)).hasSize(8);
        assertThat(CodeOwnership.foreignTables(OWNERSHIP, clean)).isEmpty();
        assertThat(CodeOwnership.foreignSettings(OWNERSHIP, clean)).isEmpty();
    }

    /** Each rule, shown a tree that breaks it, names the class that breaks it and what it names. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("breaches")
    void aBreachIsCaught(
            String fixture, BiFunction<ModuleOwnership, List<CompiledStrings>, List<String>> rule, String breach) {
        assertThat(rule.apply(OWNERSHIP, fixture(fixture))).containsExactly(breach);
    }

    static Stream<Arguments> breaches() {
        String fixture = "fixture.ownership.";
        return Stream.of(
                arguments(
                        "sqlliteral",
                        rule(CodeOwnership::foreignTables),
                        fixture + "sqlliteral.platform.infrastructure.MessageCounts (platform) names outbox_message, "
                                + "which is shared's"),
                arguments(
                        "jpql",
                        rule(CodeOwnership::foreignTables),
                        fixture
                                + "jpql.identity.application.StaffGreeting (identity) names Brand, which is branding's"),
                arguments(
                        "tableannotation",
                        rule(CodeOwnership::foreignTables),
                        fixture + "tableannotation.identity.domain.Brand (identity) names salon_brand, "
                                + "which is branding's"),
                arguments(
                        "valuesetting",
                        rule(CodeOwnership::foreignSettings),
                        fixture + "valuesetting.platform.infrastructure.IdentityKey (platform) reads "
                                + "salon.identity.signing-key, which is identity's"),
                arguments(
                        "concatsetting",
                        rule(CodeOwnership::foreignSettings),
                        fixture + "concatsetting.platform.infrastructure.IdentityKeys (platform) reads "
                                + "salon.identity., which is identity's"),
                arguments(
                        "sharedsetting",
                        rule(CodeOwnership::foreignSettings),
                        fixture + "sharedsetting.shared.infrastructure.OperatorKey (shared) reads "
                                + "salon.operator.signing-key, which is platform's"));
    }

    private static BiFunction<ModuleOwnership, List<CompiledStrings>, List<String>> rule(
            BiFunction<ModuleOwnership, List<CompiledStrings>, List<String>> rule) {
        return rule;
    }

    private static List<CompiledStrings> application() {
        try {
            Path classes = Path.of(SalonApiApplication.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            return CompiledStrings.read(classes, BASE);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<CompiledStrings> fixture(String name) {
        Path classes = output.resolve(name);
        Compilation compilation = FixtureCompiler.compile(
                List.of("-proc:none", "-classpath", FixtureCompiler.pathOf(CLASS_PATH), "-d", classes.toString()),
                sources(FixtureCompiler.FIXTURES.resolve("ownership").resolve(name)));
        assertThat(compilation.errors()).as("fixture '%s' compiles", name).isEmpty();
        List<CompiledStrings> compiled = CompiledStrings.read(classes, "fixture.ownership." + name);
        assertThat(compiled).as("fixture '%s' has classes", name).isNotEmpty();
        return compiled;
    }

    private static List<Path> sources(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
