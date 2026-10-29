package com.salonplatform.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ExportKeyTest {

    private static final String KEY = "0123456789abcdef".repeat(4);

    @Test
    void aSealedValueOpensOnlyForItsOwnPurposeAndUnaltered() {
        ExportKey key = new ExportKey(new MockEnvironment().withProperty("salon.export.key", KEY));
        byte[] plain = "the archive".getBytes(StandardCharsets.UTF_8);
        byte[] sealed = key.seal(plain, "export-archive:1");
        assertThat(sealed).isNotEqualTo(key.seal(plain, "export-archive:1"));
        assertThat(key.open(sealed, "export-archive:1")).isEqualTo(plain);
        assertThatIllegalStateException().isThrownBy(() -> key.open(sealed, "export-archive:2"));
        sealed[sealed.length - 1] ^= 1;
        assertThatIllegalStateException().isThrownBy(() -> key.open(sealed, "export-archive:1"));
    }

    @Test
    void tokensAre256BitsAndOnlyTheirHashIsStored() {
        ExportKey key = new ExportKey(new MockEnvironment().withProperty("salon.export.key", KEY));
        String token = key.newToken();
        assertThat(ExportKey.tokenShaped(token)).isTrue();
        assertThat(token).hasSize(43).isNotEqualTo(key.newToken());
        assertThat(ExportKey.tokenHash(token)).matches("[0-9a-f]{64}").doesNotContain(token);
        assertThat(ExportKey.tokenShaped(token + "x")).isFalse();
        assertThat(ExportKey.tokenShaped("../../etc/passwd")).isFalse();
    }

    @Test
    void theKeyIsRequiredAndIndependentOutsideLocal() {
        assertThatIllegalStateException().isThrownBy(() -> new ExportKey(new MockEnvironment()));
        assertThatIllegalStateException()
                .isThrownBy(() -> new ExportKey(new MockEnvironment().withProperty("salon.export.key", "short")));
        assertThatIllegalStateException()
                .isThrownBy(() -> new ExportKey(new MockEnvironment()
                        .withProperty("salon.export.key", KEY)
                        .withProperty("salon.billing.provider-key", KEY)));
    }
}
