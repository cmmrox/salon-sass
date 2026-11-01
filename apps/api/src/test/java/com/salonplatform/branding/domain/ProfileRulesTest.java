package com.salonplatform.branding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The business profile's rules (F04-S01 AC-6): every field optional, each rule a field error naming the field. */
class ProfileRulesTest {

    private final UUID branch = UUID.fromString("0b6f9f43-5a1d-4c5e-9a2b-3f3f6e1d2c10");
    private final ProfileContent empty =
            new ProfileContent(null, null, null, null, List.of(BranchContact.empty(branch)));

    private ProfileInput.Branch branchInput(String address, ProfileInput.Location location, String phone, String wa) {
        return new ProfileInput.Branch(branch.toString(), address, location, phone, wa);
    }

    private ProfileRules.Outcome check(ProfileInput input) {
        return ProfileRules.check(input, empty, "LK");
    }

    private List<String> refusedFields(ProfileInput input) {
        assertThat(check(input)).isInstanceOf(ProfileRules.Refused.class);
        return ((ProfileRules.Refused) check(input))
                .violations().stream().map(ProfileRules.Violation::field).toList();
    }

    @Test
    void textIsTrimmedEmptyMeansNotSetAndPhonesAreStoredInE164() {
        ProfileRules.Outcome outcome = check(new ProfileInput(
                "  Hello  ",
                " https://instagram.com/x ",
                "",
                null,
                List.of(branchInput(
                        "  Colombo  ",
                        new ProfileInput.Location(new BigDecimal("6.90612345"), new BigDecimal("79.8611")),
                        "011 250 4417",
                        "077 412 3300"))));

        ProfileContent content = ((ProfileRules.Accepted) outcome).content();
        assertThat(content.description()).isEqualTo("Hello");
        assertThat(content.instagram()).isEqualTo("https://instagram.com/x");
        assertThat(content.facebook()).isNull();
        assertThat(content.website()).isNull();
        BranchContact contact = content.branches().getFirst();
        assertThat(contact.address()).isEqualTo("Colombo");
        assertThat(contact.location().latitude()).isEqualByComparingTo("6.906123");
        assertThat(contact.phone()).isEqualTo("+94112504417");
        assertThat(contact.whatsapp()).isEqualTo("+94774123300");
    }

    @Test
    void anEmptyProfileIsAcceptedAndABranchNotListedKeepsItsDraft() {
        BranchContact kept = new BranchContact(branch, "Kept", null, "+94112504417", null);
        ProfileContent current = new ProfileContent(null, null, null, null, List.of(kept));

        ProfileRules.Outcome outcome =
                ProfileRules.check(new ProfileInput(null, null, null, null, List.of()), current, "LK");

        assertThat(((ProfileRules.Accepted) outcome).content().branches()).containsExactly(kept);
    }

    @Test
    void lengthsAreCountedInCharactersUpToTheirLimits() {
        String address = "අ".repeat(200);
        String description = "த".repeat(500);
        assertThat(check(new ProfileInput(
                        description, null, null, null, List.of(branchInput(address, null, null, null)))))
                .isInstanceOf(ProfileRules.Accepted.class);
        assertThat(refusedFields(new ProfileInput(
                        description + "x", null, null, null, List.of(branchInput(address + "x", null, null, null)))))
                .containsExactly("profile.description", "profile.branches[0].address");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://instagram.com/x",
                "instagram.com/x",
                "https://",
                "https://nodot",
                "https://a b.com",
                "ftp://x.com",
                "javascript:alert(1)"
            })
    void aLinkThatIsNotAnHttpsAddressIsRefused(String link) {
        assertThat(refusedFields(new ProfileInput(null, link, link, link, List.of())))
                .containsExactly("profile.links.instagram", "profile.links.facebook", "profile.links.website");
    }

    @Test
    void aLinkOverTwoHundredCharactersIsRefused() {
        String link = "https://example.com/" + "a".repeat(181);
        assertThat(link).hasSize(201);
        assertThat(refusedFields(new ProfileInput(null, null, null, link, List.of())))
                .containsExactly("profile.links.website");
        assertThat(check(new ProfileInput(null, null, null, link.substring(0, 200), List.of())))
                .isInstanceOf(ProfileRules.Accepted.class);
    }

    @Test
    void aMapPositionNeedsBothCoordinatesInRange() {
        ProfileInput.Location edge = new ProfileInput.Location(new BigDecimal("-90"), new BigDecimal("180"));
        assertThat(check(new ProfileInput(null, null, null, null, List.of(branchInput(null, edge, null, null)))))
                .isInstanceOf(ProfileRules.Accepted.class);
        for (ProfileInput.Location bad : List.of(
                new ProfileInput.Location(new BigDecimal("90.000001"), BigDecimal.ONE),
                new ProfileInput.Location(null, BigDecimal.ONE))) {
            assertThat(refusedFields(
                            new ProfileInput(null, null, null, null, List.of(branchInput(null, bad, null, null)))))
                    .containsExactly("profile.branches[0].location.latitude");
        }
        ProfileInput.Location badLongitude = new ProfileInput.Location(BigDecimal.ONE, new BigDecimal("-180.1"));
        assertThat(refusedFields(
                        new ProfileInput(null, null, null, null, List.of(branchInput(null, badLongitude, null, null)))))
                .containsExactly("profile.branches[0].location.longitude");
    }

    @Test
    void anInvalidPhoneOrALandlineForWhatsAppIsAFieldError() {
        assertThat(refusedFields(new ProfileInput(
                        null, null, null, null, List.of(branchInput(null, null, "12345", "0112504417")))))
                .containsExactly("profile.branches[0].phone", "profile.branches[0].whatsapp");
    }

    @Test
    void aBranchIdThatIsNotTheSalonsOrIsListedTwiceIsAFieldError() {
        ProfileInput.Branch foreign = new ProfileInput.Branch(UUID.randomUUID().toString(), null, null, null, null);
        ProfileInput.Branch malformed = new ProfileInput.Branch("not-a-uuid", null, null, null, null);
        ProfileInput.Branch shortened = new ProfileInput.Branch("1-1-1-1-1", null, null, null, null);
        ProfileInput.Branch upperCase =
                new ProfileInput.Branch(branch.toString().toUpperCase(), null, null, null, null);
        assertThat(refusedFields(
                        new ProfileInput(null, null, null, null, List.of(foreign, malformed, shortened, upperCase))))
                .containsExactly("profile.branches[0].id", "profile.branches[1].id", "profile.branches[2].id");
        ProfileInput.Branch twice = branchInput(null, null, null, null);
        assertThat(refusedFields(new ProfileInput(null, null, null, null, List.of(twice, twice))))
                .containsExactly("profile.branches[1].id");
    }

    @Test
    void noViolationCarriesTheValueItRefused() {
        ProfileRules.Refused refused = (ProfileRules.Refused) check(new ProfileInput(
                "x".repeat(501),
                "http://secret-link.example",
                null,
                null,
                List.of(branchInput("y".repeat(201), null, "0700000secret", null))));
        assertThat(refused.violations())
                .allSatisfy(violation -> assertThat(violation.detail()).doesNotContain("secret", "xxxxx", "yyyyy"));
    }

    @Test
    void changedFieldsNameFieldsAndNeverValues() {
        ProfileContent before = new ProfileContent("Old", null, null, null, List.of(BranchContact.empty(branch)));
        ProfileContent after = new ProfileContent(
                "New",
                "https://instagram.com/x",
                null,
                null,
                List.of(new BranchContact(
                        branch,
                        null,
                        new Coordinates(new BigDecimal("6.900000"), new BigDecimal("79.800000")),
                        null,
                        "+94774123300")));

        assertThat(after.changedFrom(before))
                .containsExactly(
                        "description",
                        "links.instagram",
                        "branches[" + branch + "].location",
                        "branches[" + branch + "].whatsapp");
        assertThat(after.changedFrom(after)).isEmpty();
        ProfileContent sameDifferentScale = new ProfileContent(
                "New",
                "https://instagram.com/x",
                null,
                null,
                List.of(new BranchContact(
                        branch,
                        null,
                        new Coordinates(new BigDecimal("6.9"), new BigDecimal("79.8")),
                        null,
                        "+94774123300")));
        assertThat(sameDifferentScale.changedFrom(after)).isEmpty();
    }
}
