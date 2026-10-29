package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.salonplatform.SalonApiApplication;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fails the build on a forbidden dependency between modules. Do not weaken a rule to get a change
 * in; move the code.
 */
class ModuleBoundariesTest {

    private static final String BASE = SalonApiApplication.class.getPackageName();

    private static final String FIXTURE = ModuleBoundariesTest.class.getPackageName() + ".fixture.";

    @Test
    void theApplicationKeepsEveryRuleBetweenModules() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
        assertThat(application.contain(BASE + ".shared.application.MetaService"))
                .as("the application's classes were found")
                .isTrue();
        assertThat(application.stream().map(javaClass -> javaClass.getPackageName()))
                .as("no test class is taken for application code")
                .noneMatch(packageName -> packageName.startsWith(FIXTURE));

        new ModuleRules(BASE).all().forEach(rule -> rule.check(application));
    }

    /** The two rules about the local hooks have something to check: the hooks are where they look. */
    @Test
    void theLocalHooksAreWhereTheRulesLookForThem() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE + ".shared.local");

        assertThat(application.stream().map(JavaClass::getSimpleName))
                .contains("TestClock", "LocalClockController", "ProbeController", "Probes", "ProbeProvider");
    }

    @Test
    void aTreeThatFollowsTheRulesPasses() {
        JavaClasses clean = fixture("clean");

        new ModuleRules(FIXTURE + "clean").all().forEach(rule -> rule.check(clean));
    }

    /** Each rule, shown a tree that breaks it, names the class that breaks it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("violations")
    void aForbiddenDependencyIsCaught(String fixture, Function<ModuleRules, ArchRule> rule, String offender) {
        EvaluationResult result = rule.apply(new ModuleRules(FIXTURE + fixture)).evaluate(fixture(fixture));

        assertThat(result.hasViolation())
                .as("the rule fails on fixture '%s'", fixture)
                .isTrue();
        assertThat(String.join("\n", result.getFailureReport().getDetails())).contains(offender);
    }

    static Stream<Arguments> violations() {
        return Stream.of(
                arguments("sharedusesmodule", rule(ModuleRules::sharedDependsOnNoOtherModule), "SharedUsesBooking"),
                arguments("usesreporting", rule(ModuleRules::nothingDependsOnReporting), "BookingUsesReporting"),
                arguments("cycle", rule(ModuleRules::modulesAreFreeOfCycles), "PaymentFacade"),
                arguments(
                        "internals",
                        rule(ModuleRules::anotherModuleIsUsedOnlyThroughItsRootPackage),
                        "PaymentUsesBookingEntity"),
                arguments("springindomain", rule(ModuleRules::domainUsesNoSpringAndNoHttp), "SpringInDomain"),
                arguments("unlisted", rule(ModuleRules::everyClassBelongsToAKnownModule), "Points"),
                arguments(
                        "localhookused", rule(ModuleRules::theLocalHooksAreUsedByNothingElse), "BookingUsesTestClock"),
                arguments("localhookalways", rule(ModuleRules::theLocalHooksLoadInTheLocalProfileOnly), "AlwaysThere"),
                arguments(
                        "localhookalways", rule(ModuleRules::theLocalHooksLoadInTheLocalProfileOnly), "AlsoElsewhere"));
    }

    private static Function<ModuleRules, ArchRule> rule(Function<ModuleRules, ArchRule> rule) {
        return rule;
    }

    private static JavaClasses fixture(String name) {
        JavaClasses classes = new ClassFileImporter().importPackages(FIXTURE + name);
        assertThat(classes).as("fixture '%s' has classes", name).isNotEmpty();
        return classes;
    }
}
