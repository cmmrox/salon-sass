package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.platform.OperatorTestSupport.Operator;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.dto.AccountInput;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decision 105: the local operator seed hook accepts the fixed roster's development password and nothing else
 * below the policy, and with {@code keepExisting} only adds the accounts that are missing, leaving every existing
 * one exactly as it is. The operator bootstrap keeps the policy in the profile that has the development password.
 */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion beside it checks.
@SuppressWarnings("unchecked")
class LocalOperatorSeedIntegrationTest {

    /** Written out here, independently of the configuration it is checked against. */
    private static final String DEVELOPMENT_PASSWORD = "123456@321";

    private static final String ROW =
            "SELECT display_name, password_hash, role, mfa_secret, version, updated_at FROM operator_user WHERE email = ?";

    @TempDir
    static Path directory;

    private static StartedApplication app;
    private static JdbcTemplate owner;
    private static JsonMapper json;
    private static OperatorCrypto crypto;
    private static OperatorTestSupport console;

    @BeforeAll
    static void start() {
        TestDatabase db = TestDatabase.createEmpty(TestPostgres.shared());
        owner = db.asOwner();
        app = db.start(
                "--spring.profiles.active=test,local",
                "--spring.flyway.locations=classpath:db/migration,classpath:db/local",
                "--salon.identity.local-key-file=" + directory.resolve("identity-keys.properties"),
                "--salon.operator.local-key-file=" + directory.resolve("operator-keys.properties"));
        json = app.bean(JsonMapper.class);
        crypto = app.bean(OperatorCrypto.class);
        console = new OperatorTestSupport(
                app.http(),
                app.port(),
                app.bean(OperatorAccountService.class),
                crypto,
                json,
                app.bean(Environment.class),
                app.bean(Clock.class));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void theSeedHookAcceptsTheDevelopmentPasswordAndTheOperatorSignsInWithMfa() {
        String secret = crypto.newTotpSecret();
        Http.Exchange seeded = seed(
                false,
                account(
                        "ruwan.dissanayake@salonops.lk",
                        "Ruwan Dissanayake",
                        "OPERATOR_ADMIN",
                        DEVELOPMENT_PASSWORD,
                        secret));
        assertThat(seeded.status()).withFailMessage("%s", seeded.body()).isEqualTo(200);
        assertThat(seeded.body()).doesNotContain(DEVELOPMENT_PASSWORD, secret);

        Map<String, Object> tokens = console.signIn(
                new Operator("ruwan.dissanayake@salonops.lk", DEVELOPMENT_PASSWORD, secret, "OPERATOR_ADMIN"));
        assertThat(tokens).containsKey("accessToken");
    }

    @Test
    void theSeedHookStillRefusesEveryOtherPasswordBelowThePolicy() {
        int n = 0;
        for (String weak : List.of("123456@322", "123456@3210", "short", " 123456@321", "123456@321 ")) {
            Http.Exchange refused =
                    seed(true, account("weak" + n++ + "@salonops.lk", "Weak Password", "OPERATOR_SUPPORT", weak, null));
            assertThat(refused.status()).as("seeding '%s'", weak).isEqualTo(400);
            assertThat(fields(refused)).as("seeding '%s'", weak).containsExactly("password");
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM operator_user WHERE email = ?",
                            Long.class,
                            "weak" + (n - 1) + "@salonops.lk"))
                    .isZero();
        }
    }

    @Test
    void keepExistingAddsOnlyMissingAccountsAndChangesNoExistingOne() {
        String secret = crypto.newTotpSecret();
        assertThat(seed(
                                false,
                                account(
                                        "shanika.abeysekera@salonops.lk",
                                        "Shanika Abeysekera",
                                        "OPERATOR_SUPPORT",
                                        DEVELOPMENT_PASSWORD,
                                        secret))
                        .status())
                .isEqualTo(200);
        Map<String, Object> before = owner.queryForMap(ROW, "shanika.abeysekera@salonops.lk");

        Http.Exchange again = seed(
                true,
                List.of(
                        account(
                                "shanika.abeysekera@salonops.lk",
                                "Someone Else",
                                "OPERATOR_ADMIN",
                                "another synthetic password",
                                crypto.newTotpSecret()),
                        account(
                                "new.operator@salonops.lk",
                                "New Operator",
                                "OPERATOR_SUPPORT",
                                DEVELOPMENT_PASSWORD,
                                null)));
        assertThat(again.status()).withFailMessage("%s", again.body()).isEqualTo(200);

        assertThat(owner.queryForMap(ROW, "shanika.abeysekera@salonops.lk")).isEqualTo(before);
        assertThat(console.signIn(new Operator(
                        "shanika.abeysekera@salonops.lk", DEVELOPMENT_PASSWORD, secret, "OPERATOR_SUPPORT")))
                .containsKey("accessToken");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM operator_user WHERE email = ?", Long.class, "new.operator@salonops.lk"))
                .isEqualTo(1);
    }

    @Test
    void keepExistingLeavesAnAccountTheHookDidNotCreateAloneInsteadOfRefusingIt() {
        assertThat(app.bean(OperatorAccountService.class)
                        .bootstrap(new AccountInput(
                                "bootstrapped@salonops.lk",
                                "Bootstrapped Operator",
                                "OPERATOR_ADMIN",
                                "a bootstrap password long enough",
                                null)))
                .isTrue();
        Map<String, Object> before = owner.queryForMap(ROW, "bootstrapped@salonops.lk");
        Http.Exchange kept = seed(
                true, account("bootstrapped@salonops.lk", "Changed", "OPERATOR_SUPPORT", DEVELOPMENT_PASSWORD, null));
        assertThat(kept.status()).withFailMessage("%s", kept.body()).isEqualTo(200);
        assertThat(owner.queryForMap(ROW, "bootstrapped@salonops.lk")).isEqualTo(before);
    }

    @Test
    void theOperatorBootstrapRefusesTheDevelopmentPassword() {
        assertThatThrownBy(() -> app.bean(OperatorAccountService.class)
                        .bootstrap(new AccountInput(
                                "bootstrap.weak@salonops.lk",
                                "Bootstrap Weak",
                                "OPERATOR_ADMIN",
                                DEVELOPMENT_PASSWORD,
                                null)))
                .isInstanceOfSatisfying(ApplicationProblemException.class, problem -> {
                    assertThat(problem.status()).isEqualTo(400);
                    assertThat(problem.errors()).extracting("field").containsExactly("password");
                });
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM operator_user WHERE email = ?", Long.class, "bootstrap.weak@salonops.lk"))
                .isZero();
    }

    private static Map<String, Object> account(
            String email, String displayName, String role, String password, String totpSecret) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("email", email);
        account.put("displayName", displayName);
        account.put("role", role);
        account.put("password", password);
        account.put("totpSecret", totpSecret);
        return account;
    }

    private static Http.Exchange seed(boolean keepExisting, Map<String, Object> account) {
        return seed(keepExisting, List.of(account));
    }

    private static Http.Exchange seed(boolean keepExisting, List<Map<String, Object>> accounts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("accounts", accounts);
        if (keepExisting) request.put("keepExisting", true);
        return app.http()
                .sendOnHost(
                        "POST",
                        "localhost:" + app.port(),
                        "/local/operator/seed",
                        Map.of("Content-Type", "application/json"),
                        json.writeValueAsString(request));
    }

    private static List<String> fields(Http.Exchange response) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>)
                json.readValue(response.body(), Map.class).get("errors");
        return errors == null
                ? List.of()
                : errors.stream().map(e -> (String) e.get("field")).toList();
    }
}
