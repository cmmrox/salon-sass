package com.salonplatform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MigrationIntegrationTest {

    private static final String HISTORY =
            "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank";

    @Test
    void theFirstStartAppliesEveryMigrationInOrderAndTheSecondAppliesNothing() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());

        List<Map<String, Object>> afterFirstStart;
        try (StartedApplication _ = database.start()) {
            afterFirstStart = database.asOwner().queryForList(HISTORY);
        }

        assertThat(afterFirstStart)
                .extracting(row -> row.get("version"), row -> row.get("description"))
                .containsExactly(
                        tuple("1", "baseline"),
                        tuple("2", "create tenant"),
                        tuple("3", "create salon brand"),
                        tuple("4", "add tenant time zone"),
                        tuple("5", "create audit entry"),
                        tuple("6", "create outbox message"),
                        tuple("7", "create scheduled job"),
                        tuple("8", "create staff identity"),
                        tuple("9", "create customer identity"),
                        tuple("10", "add customer profile"),
                        tuple("11", "create platform operator console"),
                        tuple("12", "create identity owner bootstrap"),
                        tuple("13", "create branding bootstrap"),
                        tuple("14", "create subscription billing"),
                        tuple("15", "create salon lifecycle"),
                        tuple("16", "create support sessions"),
                        tuple("17", "add outbox events"),
                        tuple("18", "create branding branches and profile"),
                        tuple("19", "create branding brand draft and images"),
                        tuple("20", "create platform holiday calendar"),
                        tuple("21", "create staff opening hours holidays and closures"),
                        tuple("22", "add platform salon currency"),
                        tuple("23", "create booking rules"),
                        tuple("24", "add branding default branch id"),
                        tuple("25", "create catalogue"),
                        tuple("26", "add catalogue variants popular and deposits"));
        assertThat(afterFirstStart).allSatisfy(row -> assertThat(row).containsEntry("success", true));

        try (StartedApplication application = database.start()) {
            assertThat(database.asOwner().queryForList(HISTORY)).isEqualTo(afterFirstStart);
            assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);
        }
    }
}
