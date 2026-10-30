package com.salonplatform.shared.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.SalonApiApplication;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * CR-001: no code can be returned that is not listed. A problem body's {@code code} is written in
 * two places only, and both take it from {@link ErrorCode}, whose values are the contract's
 * enumeration ({@code OpenApiShapeTest}). This keeps it so: nothing else may put a property on a
 * problem body, and the two that write a code must take it from the list.
 */
class OnlyListedCodesTest {

    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(SalonApiApplication.class.getPackageName());

    @Test
    void onlyProblemsPutsAPropertyOnAProblemBody() {
        assertThat(APPLICATION.contain(Problems.class))
                .as("the application's classes were found")
                .isTrue();
        // The rule below can see such a call: it finds the one that is allowed.
        classes()
                .that()
                .haveFullyQualifiedName(Problems.class.getName())
                .should()
                .callMethod(ProblemDetail.class, "setProperty", String.class, Object.class)
                .check(APPLICATION);

        noClasses()
                .that()
                .doNotHaveFullyQualifiedName(Problems.class.getName())
                .should()
                .callMethod(ProblemDetail.class, "setProperty", String.class, Object.class)
                .orShould()
                .callMethod(ProblemDetail.class, "setProperties", Map.class)
                .as("a problem body's code, traceId and errors are written by Problems and nowhere else")
                .check(APPLICATION);
    }

    @Test
    void theTwoThatWriteACodeTakeItFromTheList() {
        classes()
                .that()
                .haveFullyQualifiedName(Problems.class.getName())
                .or()
                .haveFullyQualifiedName(ProblemErrorReportValve.class.getName())
                .should()
                .callMethod(ErrorCode.class, "code")
                .as("the code written to a problem body is an ErrorCode's")
                .check(APPLICATION);
    }
}
