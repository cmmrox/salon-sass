package com.salonplatform.shared.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Decision 120, platform review M8: every ciphertext carries the id of its key, so the key can be rotated; a value
 * written under an earlier key, and a value written before key ids existed, are still read.
 */
class KeyRingTest {

    private static final byte[] FIRST = key(1);

    private static final byte[] SECOND = key(2);

    private static final String PURPOSE = "totp:salon:account";

    @Test
    void aNewValueCarriesTheIdOfTheKeyThatEncryptedIt() {
        KeyRing ring = new KeyRing("test", FIRST, List.of());

        String encrypted = ring.encrypt(PURPOSE, "a secret");

        assertThat(encrypted.split("\\.")).hasSize(3);
        assertThat(encrypted).startsWith(ring.currentKeyId() + ".");
        assertThat(ring.currentKeyId())
                .matches("[0-9a-f]{8}")
                .isNotEqualTo(new KeyRing("test", SECOND, List.of()).currentKeyId());
        assertThat(ring.decrypt(PURPOSE, encrypted)).isEqualTo("a secret");
    }

    @Test
    void aValueWrittenBeforeKeyIdsExistedIsStillRead() {
        String legacy = legacyText(FIRST, PURPOSE, "the fixed roster's authenticator secret");

        assertThat(new KeyRing("test", FIRST, List.of()).decrypt(PURPOSE, legacy))
                .isEqualTo("the fixed roster's authenticator secret");
        assertThat(new KeyRing("test", SECOND, List.of(FIRST)).decrypt(PURPOSE, legacy))
                .as("and still after the key it was written under has been rotated out")
                .isEqualTo("the fixed roster's authenticator secret");
    }

    @Test
    void aValueWrittenUnderAnEarlierKeyIsReadAfterRotationAndNewValuesUseTheNewKey() {
        String underFirst = new KeyRing("test", FIRST, List.of()).encrypt(PURPOSE, "before");
        KeyRing rotated = new KeyRing("test", SECOND, List.of(FIRST));

        assertThat(rotated.decrypt(PURPOSE, underFirst)).isEqualTo("before");
        assertThat(rotated.encrypt(PURPOSE, "after")).startsWith(rotated.currentKeyId() + ".");
        assertThatIllegalStateException()
                .as("without the earlier key it cannot be read")
                .isThrownBy(() -> new KeyRing("test", SECOND, List.of()).decrypt(PURPOSE, underFirst));
    }

    @Test
    void anAlteredValueOrOneForAnotherPurposeIsRefused() {
        KeyRing ring = new KeyRing("test", FIRST, List.of());
        String encrypted = ring.encrypt(PURPOSE, "a secret");
        String[] parts = encrypted.split("\\.");
        String otherId = new KeyRing("test", SECOND, List.of()).currentKeyId();

        assertThatIllegalStateException().isThrownBy(() -> ring.decrypt("another purpose", encrypted));
        assertThatIllegalStateException()
                .isThrownBy(() -> ring.decrypt(PURPOSE, otherId + "." + parts[1] + "." + parts[2]));
        assertThatIllegalStateException()
                .isThrownBy(() -> ring.decrypt(PURPOSE, parts[0] + "." + parts[1] + "." + parts[1]));
    }

    @Test
    void binaryValuesCarryTheirKeyIdAndOldOnesAreStillRead() {
        byte[] plain = "an export archive".getBytes(StandardCharsets.UTF_8);
        byte[] underFirst = new KeyRing("test", FIRST, List.of()).seal(plain, "export-archive:1");
        byte[] legacy = legacyBinary(FIRST, "export-archive:1", plain);
        KeyRing rotated = new KeyRing("test", SECOND, List.of(FIRST));

        assertThat(new String(underFirst, 0, 3, StandardCharsets.US_ASCII)).isEqualTo("SK1");
        assertThat(rotated.open(underFirst, "export-archive:1")).isEqualTo(plain);
        assertThat(rotated.open(legacy, "export-archive:1")).isEqualTo(plain);
        assertThat(rotated.open(rotated.seal(plain, "export-archive:1"), "export-archive:1"))
                .isEqualTo(plain);
        assertThatIllegalStateException().isThrownBy(() -> rotated.open(underFirst, "export-archive:2"));
        assertThatIllegalStateException()
                .isThrownBy(() -> new KeyRing("test", SECOND, List.of()).open(legacy, "export-archive:1"));
    }

    @Test
    void theSameKeyTwiceIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> new KeyRing("test", FIRST, List.of(SECOND, FIRST)));
    }

    @Test
    void earlierKeysAreReadFromACommaSeparatedSetting() {
        String hex = java.util.HexFormat.of().formatHex(FIRST) + ", "
                + java.util.HexFormat.of().formatHex(SECOND);

        assertThat(KeyRing.keysOf(hex, "setting")).containsExactly(FIRST, SECOND);
        assertThat(KeyRing.keysOf(" ", "setting")).isEmpty();
        assertThatIllegalStateException().isThrownBy(() -> KeyRing.keysOf("not-a-key", "setting"));
    }

    /** A value as IdentityCrypto and OperatorCrypto wrote it before key ids: nonce and ciphertext, Base64. */
    private static String legacyText(byte[] key, String purpose, String plain) {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        byte[] sealed = gcm(key, nonce, purpose, plain.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(nonce) + "."
                + Base64.getEncoder().encodeToString(sealed);
    }

    /** A value as ExportKey sealed it before key ids: the nonce followed by the ciphertext. */
    private static byte[] legacyBinary(byte[] key, String purpose, byte[] plain) {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        byte[] sealed = gcm(key, nonce, purpose, plain);
        return ByteBuffer.allocate(nonce.length + sealed.length)
                .put(nonce)
                .put(sealed)
                .array();
    }

    private static byte[] gcm(byte[] key, byte[] nonce, String purpose, byte[] plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(plain);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] key(int seed) {
        byte[] key = new byte[32];
        new java.util.Random(seed).nextBytes(key);
        return key;
    }
}
