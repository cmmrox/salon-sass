package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.domain.*;
import org.junit.jupiter.api.Test;

class IdentityRulesTest {
    @Test
    void identifiersNormalizeWithoutSharingSalons() {
        assertThat(Identifiers.email(" Some.One@EXAMPLE.COM ")).isEqualTo("some.one@example.com");
        assertThat(Identifiers.mobile("077 123 4567")).isEqualTo("+94771234567");
        assertThat(Identifiers.mobile("94771234567")).isEqualTo("+94771234567");
        assertThatIllegalArgumentException().isThrownBy(() -> Identifiers.mobile("+12025550123"));
    }

    @Test
    void longUnicodePasswordsAndSpacesAreSupported() {
        String generated = java.util.UUID.randomUUID() + " 🪮";
        assertThat(PasswordPolicy.valid(generated)).isTrue();
        assertThat(PasswordPolicy.valid("a".repeat(14))).isFalse();
        assertThat(PasswordPolicy.valid("a".repeat(129))).isFalse();
        assertThat(PasswordPolicy.valid("password123456789")).isFalse();
    }

    @Test
    void permissionsAndProfessionalScopeMatchPolicy() {
        assertThat(StaffRole.OWNER.permissions(false)).contains("staff.manage", "financial.read", "customer.export");
        assertThat(StaffRole.MANAGER.permissions(false))
                .contains("staff.list", "audit.list")
                .doesNotContain("staff.manage");
        assertThat(StaffRole.RECEPTIONIST.permissions(false))
                .contains("calendar.read")
                .doesNotContain("financial.read", "staff.list");
        assertThat(StaffRole.PROFESSIONAL.scope(false)).isEqualTo("own");
        assertThat(StaffRole.PROFESSIONAL.permissions(false)).doesNotContain("financial.read");
        assertThat(StaffRole.PROFESSIONAL.permissions(true)).contains("financial.read");
        assertThat(StaffRole.ACCOUNTANT.permissions(false))
                .contains("financial.read")
                .doesNotContain("customer.read");
    }
}
