package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decision 105: the local staff seed hook accepts the fixed roster's development password, which is below the
 * password policy, and nothing else below it; adding staff and changing a password through the product keep the
 * policy, in the very profile that has the development password.
 */
// JSON bodies are read as untyped maps (Map.class); each cast states the shape the assertion beside it checks.
@SuppressWarnings("unchecked")
class LocalDevelopmentPasswordIntegrationTest {

    /** Written out here, independently of the configuration it is checked against. */
    private static final String DEVELOPMENT_PASSWORD = "123456@321";

    @TempDir
    static Path directory;

    private static StartedApplication app;
    private static JsonMapper json;
    private static IdentityCrypto crypto;
    private static Clock clock;
    private static String alpha;

    @BeforeAll
    static void start() {
        app = TestDatabase.createEmpty(TestPostgres.shared())
                .start(
                        "--spring.profiles.active=test,local",
                        "--spring.flyway.locations=classpath:db/migration,classpath:db/local",
                        "--salon.identity.local-key-file=" + directory.resolve("keys.properties"));
        json = app.bean(JsonMapper.class);
        crypto = app.bean(IdentityCrypto.class);
        clock = app.bean(Clock.class);
        alpha = "alpha.localhost:" + app.port();
    }

    @AfterAll
    static void stop() {
        if (app != null) app.close();
    }

    @Test
    void theSeedHookAcceptsTheDevelopmentPasswordAndTheAccountSignsInWithIt() {
        String secret = crypto.newTotpSecret();
        Http.Exchange seeded = seed(List.of(
                account("nadeesha.perera@alphasalon.lk", "+94771234501", DEVELOPMENT_PASSWORD, "OWNER", secret),
                account("kasun.jayawardena@alphasalon.lk", "+94771234502", DEVELOPMENT_PASSWORD, "MANAGER", null)));
        assertThat(seeded.status()).withFailMessage("%s", seeded.body()).isEqualTo(200);
        assertThat(seeded.body()).doesNotContain(DEVELOPMENT_PASSWORD, secret);

        Http.Exchange manager = signIn("kasun.jayawardena@alphasalon.lk", DEVELOPMENT_PASSWORD);
        assertThat(manager.status()).withFailMessage("%s", manager.body()).isEqualTo(200);
        assertThat((Map<String, Object>) body(manager).get("credentials")).containsKey("accessToken");

        Http.Exchange owner = signIn("nadeesha.perera@alphasalon.lk", DEVELOPMENT_PASSWORD);
        assertThat(owner.status()).withFailMessage("%s", owner.body()).isEqualTo(200);
        assertThat(body(owner).get("credentials")).isNull();
        assertThat(body(owner)).containsKey("challengeId");
        Http.Exchange verified = verify(body(owner), secret);
        assertThat(verified.status()).withFailMessage("%s", verified.body()).isEqualTo(200);
        assertThat(body(verified)).containsKey("accessToken");
    }

    @Test
    void theSeedHookStillRefusesEveryOtherPasswordBelowThePolicy() {
        int n = 0;
        for (String weak : List.of("123456@322", "123456@3210", "short", " 123456@321", "123456@321 ")) {
            Http.Exchange refused = seed(account("weak" + n++ + "@alphasalon.lk", null, weak, "MANAGER", null));
            assertThat(refused.status()).as("seeding '%s'", weak).isEqualTo(400);
            assertThat(fields(refused)).as("seeding '%s'", weak).containsExactly("password");
        }
    }

    @Test
    void addingStaffThroughTheProductRefusesTheDevelopmentPassword() {
        String secret = crypto.newTotpSecret();
        assertThat(seed(account("owner.add@alphasalon.lk", null, DEVELOPMENT_PASSWORD, "OWNER", secret))
                        .status())
                .isEqualTo(200);
        Http.Exchange verified = verify(body(signIn("owner.add@alphasalon.lk", DEVELOPMENT_PASSWORD)), secret);
        assertThat(verified.status()).withFailMessage("%s", verified.body()).isEqualTo(200);
        String ownerToken = (String) body(verified).get("accessToken");
        Map<String, Object> staff = new HashMap<>();
        staff.put("email", "new.staff@alphasalon.lk");
        staff.put("password", DEVELOPMENT_PASSWORD);
        staff.put("role", "RECEPTIONIST");
        Http.Exchange refused = call("POST", "/staff", staff, ownerToken);
        assertThat(refused.status()).withFailMessage("%s", refused.body()).isEqualTo(400);
        assertThat(fields(refused)).containsExactly("password");
        assertThat(signIn("new.staff@alphasalon.lk", DEVELOPMENT_PASSWORD).status())
                .isEqualTo(401);
    }

    @Test
    void changingAPasswordThroughTheProductRefusesTheDevelopmentPassword() {
        String policyPassword = "a synthetic password long enough";
        assertThat(seed(account("password.change@alphasalon.lk", null, policyPassword, "MANAGER", null))
                        .status())
                .isEqualTo(200);
        Http.Exchange signedIn = signIn("password.change@alphasalon.lk", policyPassword);
        assertThat(signedIn.status()).withFailMessage("%s", signedIn.body()).isEqualTo(200);
        String token = (String) ((Map<String, Object>) body(signedIn).get("credentials")).get("accessToken");
        Http.Exchange refused = call(
                "PUT",
                "/staff/password",
                Map.of("oldPassword", policyPassword, "newPassword", DEVELOPMENT_PASSWORD),
                token);
        assertThat(refused.status()).withFailMessage("%s", refused.body()).isEqualTo(400);
        assertThat(fields(refused)).containsExactly("newPassword");
        assertThat(signIn("password.change@alphasalon.lk", DEVELOPMENT_PASSWORD).status())
                .isEqualTo(401);
    }

    @Test
    void onlyTheLocalProfileConfiguresTheDevelopmentPassword() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources"))) {
            List<String> configuring = files.filter(
                            file -> file.getFileName().toString().startsWith("application"))
                    .filter(file -> read(file).contains("development-password")
                            || read(file).contains(DEVELOPMENT_PASSWORD))
                    .map(file -> file.getFileName().toString())
                    .toList();
            assertThat(configuring).containsExactly("application-local.yaml");
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Object> account(
            String email, String mobile, String password, String role, String totpSecret) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("email", email);
        if (mobile != null) account.put("mobile", mobile);
        account.put("password", password);
        account.put("role", role);
        if (totpSecret != null) account.put("totpSecret", totpSecret);
        return account;
    }

    private static Http.Exchange seed(Map<String, Object> account) {
        return seed(List.of(account));
    }

    private static Http.Exchange seed(List<Map<String, Object>> accounts) {
        return app.http()
                .postJsonOnHost(alpha, "/local/identity/seed", json.writeValueAsString(Map.of("accounts", accounts)));
    }

    private static Http.Exchange signIn(String identifier, String password) {
        return call(
                "POST",
                "/staff/sign-in",
                Map.of("identifier", identifier, "password", password, "deviceLabel", "JUnit device"),
                null);
    }

    private static Http.Exchange verify(Map<String, Object> challenge, String secret) {
        return call(
                "POST",
                "/staff/mfa/verify",
                Map.of(
                        "challengeId",
                        challenge.get("challengeId"),
                        "challengeToken",
                        challenge.get("challengeToken"),
                        "code",
                        crypto.totpCode(secret, clock.instant().getEpochSecond() / 30)),
                null);
    }

    private static Http.Exchange call(String method, String path, Object request, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return app.http()
                .sendOnHost(
                        method,
                        alpha,
                        "/api/v1/identity" + path,
                        headers,
                        request == null ? null : json.writeValueAsString(request));
    }

    private static Map<String, Object> body(Http.Exchange response) {
        return json.readValue(response.body(), Map.class);
    }

    private static List<String> fields(Http.Exchange response) {
        List<Map<String, Object>> errors =
                (List<Map<String, Object>>) body(response).get("errors");
        return errors == null
                ? List.of()
                : errors.stream().map(e -> (String) e.get("field")).toList();
    }
}
