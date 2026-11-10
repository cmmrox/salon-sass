package com.salonplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.groups.Tuple;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MigrationIntegrationTest {

    private static final String HISTORY =
            "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank";

    /** Flyway's own naming rule: V<version>__<description>.sql, underscores read as dots and spaces. */
    private static final Pattern MIGRATION = Pattern.compile("V([0-9_]+)__(.+)\\.sql");

    /**
     * Every versioned migration the build ships, as Flyway records it, in version order. Read from
     * the migration folder itself, so that adding a migration does not fail this test merely by
     * existing: the test still proves that a first start applies each of them, in order, and that
     * a second start applies nothing.
     */
    private static List<Tuple> shippedMigrations() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/V*__*.sql");
        return Stream.of(files)
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .map(MIGRATION::matcher)
                .filter(Matcher::matches)
                .sorted(Comparator.comparing(
                        name -> MigrationVersion.fromVersion(name.group(1).replace('_', '.'))))
                .map(name ->
                        tuple(name.group(1).replace('_', '.'), name.group(2).replace('_', ' ')))
                .toList();
    }

    @Test
    void theFirstStartAppliesEveryMigrationInOrderAndTheSecondAppliesNothing() throws IOException {
        List<Tuple> shipped = shippedMigrations();
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
