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
        KeyRing ring = new KeyRing("test", FIRST, List.of(), true);

        String encrypted = ring.encrypt(PURPOSE, "a secret");

        assertThat(encrypted.split("\\.")).hasSize(3);
        assertThat(encrypted).startsWith(ring.currentKeyId() + ".");
        assertThat(ring.currentKeyId())
                .matches("[0-9a-f]{8}")
                .isNotEqualTo(new KeyRing("test", SECOND, List.of(), true).currentKeyId());
        assertThat(ring.decrypt(PURPOSE, encrypted)).isEqualTo("a secret");
    }

    @Test
    void aValueWrittenBeforeKeyIdsExistedIsStillRead() {
        String legacy = legacyText(FIRST, PURPOSE, "the fixed roster's authenticator secret");

        assertThat(new KeyRing("test", FIRST, List.of(), true).decrypt(PURPOSE, legacy))
                .isEqualTo("the fixed roster's authenticator secret");
        assertThat(new KeyRing("test", SECOND, List.of(FIRST), true).decrypt(PURPOSE, legacy))
                .as("and still after the key it was written under has been rotated out")
                .isEqualTo("the fixed roster's authenticator secret");
    }

    @Test
    void aValueWrittenUnderAnEarlierKeyIsReadAfterRotationAndNewValuesUseTheNewKey() {
        String underFirst = new KeyRing("test", FIRST, List.of(), true).encrypt(PURPOSE, "before");
        KeyRing rotated = new KeyRing("test", SECOND, List.of(FIRST), true);

        assertThat(rotated.decrypt(PURPOSE, underFirst)).isEqualTo("before");
        assertThat(rotated.encrypt(PURPOSE, "after")).startsWith(rotated.currentKeyId() + ".");
        assertThatIllegalStateException()
                .as("without the earlier key it cannot be read")
                .isThrownBy(() -> new KeyRing("test", SECOND, List.of(), true).decrypt(PURPOSE, underFirst));
    }

    @Test
    void anAlteredValueOrOneForAnotherPurposeIsRefused() {
        KeyRing ring = new KeyRing("test", FIRST, List.of(), true);
        String encrypted = ring.encrypt(PURPOSE, "a secret");
        String[] parts = encrypted.split("\\.");
        String otherId = new KeyRing("test", SECOND, List.of(), true).currentKeyId();

        assertThatIllegalStateException().isThrownBy(() -> ring.decrypt("another purpose", encrypted));
        assertThatIllegalStateException()
                .isThrownBy(() -> ring.decrypt(PURPOSE, otherId + "." + parts[1] + "." + parts[2]));
        assertThatIllegalStateException()
                .isThrownBy(() -> ring.decrypt(PURPOSE, parts[0] + "." + parts[1] + "." + parts[1]));
    }

    @Test
    void binaryValuesCarryTheirKeyIdAndOldOnesAreStillRead() {
        byte[] plain = "an export archive".getBytes(StandardCharsets.UTF_8);
        byte[] underFirst = new KeyRing("test", FIRST, List.of(), true).seal(plain, "export-archive:1");
        byte[] legacy = legacyBinary(FIRST, "export-archive:1", plain);
        KeyRing rotated = new KeyRing("test", SECOND, List.of(FIRST), true);

        assertThat(new String(underFirst, 0, 3, StandardCharsets.US_ASCII)).isEqualTo("SK1");
        assertThat(rotated.open(underFirst, "export-archive:1")).isEqualTo(plain);
        assertThat(rotated.open(legacy, "export-archive:1")).isEqualTo(plain);
        assertThat(rotated.open(rotated.seal(plain, "export-archive:1"), "export-archive:1"))
                .isEqualTo(plain);
        assertThatIllegalStateException().isThrownBy(() -> rotated.open(underFirst, "export-archive:2"));
        assertThatIllegalStateException()
                .isThrownBy(() -> new KeyRing("test", SECOND, List.of(), true).open(legacy, "export-archive:1"));
    }

    /**
     * The rolling deployment's first step: with key ids not yet written, what this version writes is exactly what the
     * previous version wrote, so an instance still running it reads every new value.
     */
    @Test
    void untilKeyIdsAreWrittenANewValueIsInTheFormatThePreviousVersionReads() {
        KeyRing ring = new KeyRing("test", FIRST, List.of(), false);

        String text = ring.encrypt(PURPOSE, "a secret");
        byte[] binary = ring.seal("an archive".getBytes(StandardCharsets.UTF_8), "export-archive:1");

        assertThat(text.split("\\.")).hasSize(2);
        assertThat(previousTextReader(FIRST, PURPOSE, text)).isEqualTo("a secret");
        assertThat(new String(previousBinaryReader(FIRST, "export-archive:1", binary), StandardCharsets.UTF_8))
                .isEqualTo("an archive");
    }

    @Test
    void bothFormatsAreReadWhicheverIsWritten() {
        KeyRing withoutIds = new KeyRing("test", FIRST, List.of(), false);
        KeyRing withIds = new KeyRing("test", FIRST, List.of(), true);
        byte[] plain = "an archive".getBytes(StandardCharsets.UTF_8);

        assertThat(withIds.encrypt(PURPOSE, "x")).startsWith(withIds.currentKeyId() + ".");
        assertThat(withIds.decrypt(PURPOSE, withoutIds.encrypt(PURPOSE, "written without")))
                .isEqualTo("written without");
        assertThat(withoutIds.decrypt(PURPOSE, withIds.encrypt(PURPOSE, "written with")))
                .isEqualTo("written with");
        assertThat(withIds.open(withoutIds.seal(plain, "p"), "p")).isEqualTo(plain);
        assertThat(withoutIds.open(withIds.seal(plain, "p"), "p")).isEqualTo(plain);
    }

    @Test
    void withoutKeyIdsAValueWrittenUnderAnEarlierKeyIsStillReadAfterRotation() {
        String underFirst = new KeyRing("test", FIRST, List.of(), false).encrypt(PURPOSE, "before");
        byte[] sealedUnderFirst = new KeyRing("test", FIRST, List.of(), false).seal(new byte[] {1, 2, 3}, "p");
        KeyRing rotated = new KeyRing("test", SECOND, List.of(FIRST), false);

        assertThat(rotated.decrypt(PURPOSE, underFirst)).isEqualTo("before");
        assertThat(rotated.open(sealedUnderFirst, "p")).containsExactly(1, 2, 3);
    }

    @Test
    void theSettingIsOffUnlessItSaysOtherwise() {
        assertThat(KeyRing.writesKeyIds(new org.springframework.mock.env.MockEnvironment()))
                .isFalse();
        assertThat(KeyRing.writesKeyIds(
                        new org.springframework.mock.env.MockEnvironment().withProperty(KeyRing.WRITE_KEY_IDS, "true")))
                .isTrue();
    }

    /** IdentityCrypto.decrypt and OperatorCrypto.decrypt as they were before key ids. */
    private static String previousTextReader(byte[] key, String purpose, String encrypted) {
        String[] parts = encrypted.split("\\.");
        return new String(
                gcmOpen(
                        key,
                        Base64.getDecoder().decode(parts[0]),
                        purpose,
                        Base64.getDecoder().decode(parts[1])),
                StandardCharsets.UTF_8);
    }

    /** ExportKey.open as it was before key ids. */
    private static byte[] previousBinaryReader(byte[] key, String purpose, byte[] sealed) {
        return gcmOpen(
                key,
                java.util.Arrays.copyOf(sealed, 12),
                purpose,
                java.util.Arrays.copyOfRange(sealed, 12, sealed.length));
    }

    private static byte[] gcmOpen(byte[] key, byte[] nonce, String purpose, byte[] sealed) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void theSameKeyTwiceIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> new KeyRing("test", FIRST, List.of(SECOND, FIRST), true));
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
