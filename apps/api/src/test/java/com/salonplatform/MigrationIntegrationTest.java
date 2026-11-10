package com.salonplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationIntegrationTest {

    private static final String HISTORY =
            "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank";

    /**
     * The project's rule (docs/engineering/backend.md, "Modules"): a migration is
     * V&lt;number&gt;__&lt;description&gt;.sql, its number a whole number, numbered in sequence from 1.
     * Flyway would also apply V1.1__x.sql or V1_1__x.sql; the rule has no such names.
     */
    private static final String RULE =
            "docs/engineering/backend.md, \"Modules\": a migration is V<number>__<description>.sql,"
                    + " the number a whole number, numbered in sequence from 1";

    private static final Pattern MIGRATION = Pattern.compile("V([1-9][0-9]*)__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql");

    /** Every file of the migration folder that breaks the rule, each with the rule it breaks. */
    static List<String> ruleBreaks(List<String> names) {
        List<String> breaks = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        for (String name : names) {
            Matcher match = MIGRATION.matcher(name);
            if (match.matches()) {
                numbers.add(Integer.parseInt(match.group(1)));
            } else {
                breaks.add(name + " does not follow the rule (" + RULE + ")");
            }
        }
        List<Integer> sorted = numbers.stream().sorted().toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (sorted.get(index) != index + 1) {
                breaks.add("the numbers are " + sorted + ", not 1 to " + sorted.size() + " (" + RULE + ")");
                break;
            }
        }
        return breaks;
    }

    private static List<String> migrationFiles() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*");
        return Stream.of(files)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Every migration the build ships, as Flyway records it (version, description), in version
     * order. Read from the migration folder itself, so that adding a migration does not fail this
     * test merely by existing: the test still proves that a first start applies each of them, in
     * order, and that a second start applies nothing.
     */
    private static List<Tuple> shippedMigrations(List<String> names) {
        return names.stream()
                .map(MIGRATION::matcher)
                .filter(Matcher::matches)
                .sorted(Comparator.comparingInt(name -> Integer.parseInt(name.group(1))))
                .map(name -> tuple(name.group(1), name.group(2).replace('_', ' ')))
                .toList();
    }

    @Test
    void theRuleRefusesANameFlywayWouldApplyButTheProjectDoesNotUse() {
        assertThat(ruleBreaks(List.of("V1__baseline.sql", "V2__create_tenant.sql")))
                .isEmpty();
        for (String name :
                List.of("V1.1__hotfix.sql", "V2_1__hotfix.sql", "V03__x.sql", "R__view.sql", "v3__x.sql", "V3_x.sql")) {
            assertThat(ruleBreaks(List.of("V1__baseline.sql", "V2__create_tenant.sql", name)))
                    .as(name)
                    .singleElement()
                    .asString()
                    .contains(name, RULE);
        }
        assertThat(ruleBreaks(List.of("V1__baseline.sql", "V3__x.sql")))
                .singleElement()
                .asString()
                .contains("not 1 to 2", RULE);
    }

    @Test
    void theFirstStartAppliesEveryMigrationInOrderAndTheSecondAppliesNothing() throws IOException {
        List<String> names = migrationFiles();
        assertThat(ruleBreaks(names)).as(RULE).isEmpty();
        List<Tuple> shipped = shippedMigrations(names);
        // The folder was found and read as Flyway reads it: the baseline comes first, and the
        // migrations of the first features are there under the names Flyway gives them.
        assertThat(shipped)
                .startsWith(tuple("1", "baseline"), tuple("2", "create tenant"))
                .contains(tuple("33", "add hold length and assignment"), tuple("34", "clear deleted salon profile"));

        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());

        List<Map<String, Object>> afterFirstStart;
        try (StartedApplication _ = database.start()) {
            afterFirstStart = database.asOwner().queryForList(HISTORY);
        }

        assertThat(afterFirstStart)
                .extracting(row -> row.get("version"), row -> row.get("description"))
                .containsExactlyElementsOf(shipped);
        assertThat(afterFirstStart).allSatisfy(row -> assertThat(row).containsEntry("success", true));

        try (StartedApplication application = database.start()) {
            assertThat(database.asOwner().queryForList(HISTORY)).isEqualTo(afterFirstStart);
            assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);
        }
    }
}
