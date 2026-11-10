package com.salonplatform.shared.secrets;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * One purpose's encryption keys, so that the key can be rotated (decision 120, platform review M8): the current key,
 * which encrypts every new value, and the earlier ones, which only decrypt. AES-256-GCM with a fresh 96-bit nonce
 * per value and the value's purpose as associated data.
 *
 * <p>Every value it encrypts carries the id of its key: the first four bytes of a SHA-256 of the key, which says
 * nothing of the key and needs no configuration. A text value is {@code <key id>.<nonce>.<ciphertext>} (Base64),
 * a binary one {@code "SK1"} then the key id, the nonce and the ciphertext. A value written before key ids existed
 * ({@code <nonce>.<ciphertext>}, or the nonce and the ciphertext) is still read: it is tried with each key, the
 * current one first, and authenticated encryption tells the right one. Such a value is never rewritten here.
 */
public final class KeyRing {

    private static final int NONCE = 12;

    private static final int TAG_BITS = 128;

    private static final byte[] MARK = "SK1".getBytes(StandardCharsets.US_ASCII);

    private static final int ID_BYTES = 4;

    private final String name;

    private final SecretKeySpec current;

    private final String currentId;

    /** Every key by its id, the current one first. */
    private final Map<String, SecretKeySpec> byId;

    /** The ids in the order a value with no id tries them: the current key first, then the earlier ones. */
    private final List<String> order;

    private final SecureRandom random = new SecureRandom();

    /**
     * @param name what the keys are for, in error messages ({@code identity encryption})
     * @param current the key that encrypts
     * @param earlier keys that decrypt values written while they were current, newest first
     */
    public KeyRing(String name, byte[] current, List<byte[]> earlier) {
        this.name = name;
        this.current = aes(current);
        this.currentId = idOf(current);
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        keys.put(currentId, this.current);
        for (byte[] key : earlier) {
            if (keys.putIfAbsent(idOf(key), aes(key)) != null) {
                throw new IllegalStateException(name + ": two of its keys are the same, or share a key id");
            }
        }
        this.byId = Map.copyOf(keys);
        this.order = List.copyOf(keys.keySet());
    }

    /** The id of the key that encrypts. */
    public String currentKeyId() {
        return currentId;
    }

    /** Parses a comma-separated list of 64-hex-character keys, as a setting holds them; blank is none. */
    public static List<byte[]> keysOf(String setting, String settingName) {
        List<byte[]> keys = new ArrayList<>();
        if (setting == null || setting.isBlank()) {
            return keys;
        }
        for (String value : setting.split(",")) {
            String key = value.strip();
            if (!key.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalStateException(settingName + " must list 64-hex-character keys, separated by commas");
            }
            keys.add(HexFormat.of().parseHex(key));
        }
        return keys;
    }

    /** Encrypts a text value for one purpose, under the current key, carrying its id. */
    public String encrypt(String purpose, String plain) {
        byte[] nonce = nonce();
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, current, nonce, purpose, plain.getBytes(StandardCharsets.UTF_8));
        Base64.Encoder base64 = Base64.getEncoder();
        return currentId + "." + base64.encodeToString(nonce) + "." + base64.encodeToString(sealed);
    }

    /** Decrypts a text value encrypted for this purpose, under any of the keys; fails when it was altered. */
    public String decrypt(String purpose, String encrypted) {
        try {
            String[] parts = encrypted.split("\\.");
            Base64.Decoder base64 = Base64.getDecoder();
            if (parts.length == 3) {
                SecretKeySpec key = byId.get(parts[0]);
                if (key != null) {
                    return text(cipher(
                            Cipher.DECRYPT_MODE, key, base64.decode(parts[1]), purpose, base64.decode(parts[2])));
                }
            } else if (parts.length == 2) {
                byte[] nonce = base64.decode(parts[0]);
                byte[] sealed = base64.decode(parts[1]);
                return text(
                        anyKey(nonce, purpose, sealed).orElseThrow(() -> new IllegalStateException("no key opens it")));
            }
        } catch (RuntimeException ex) {
            // Below: a value that cannot be read says nothing of why.
        }
        throw new IllegalStateException("A stored " + name + " value could not be authenticated");
    }

    /** Encrypts a binary value for one purpose, under the current key, carrying its id. */
    public byte[] seal(byte[] plain, String purpose) {
        byte[] nonce = nonce();
        byte[] sealed = cipher(Cipher.ENCRYPT_MODE, current, nonce, purpose, plain);
        return ByteBuffer.allocate(MARK.length + ID_BYTES + NONCE + sealed.length)
                .put(MARK)
                .put(HexFormat.of().parseHex(currentId))
                .put(nonce)
                .put(sealed)
                .array();
    }

    /** Decrypts a binary value sealed for this purpose, under any of the keys; fails when it was altered. */
    public byte[] open(byte[] sealed, String purpose) {
        int header = MARK.length + ID_BYTES;
        if (sealed.length > header + NONCE && Arrays.equals(sealed, 0, MARK.length, MARK, 0, MARK.length)) {
            SecretKeySpec key = byId.get(HexFormat.of().formatHex(sealed, MARK.length, header));
            if (key != null) {
                try {
                    return cipher(
                            Cipher.DECRYPT_MODE,
                            key,
                            Arrays.copyOfRange(sealed, header, header + NONCE),
                            purpose,
                            Arrays.copyOfRange(sealed, header + NONCE, sealed.length));
                } catch (RuntimeException ex) {
                    // A value written before key ids whose nonce happens to begin like a marked one: read below.
                }
            }
        }
        if (sealed.length > NONCE) {
            Optional<byte[]> legacy =
                    anyKey(Arrays.copyOf(sealed, NONCE), purpose, Arrays.copyOfRange(sealed, NONCE, sealed.length));
            if (legacy.isPresent()) {
                return legacy.get();
            }
        }
        throw new IllegalStateException("A stored " + name + " value could not be decrypted");
    }

    /** A value written before key ids: each key in turn, the current one first. */
    private Optional<byte[]> anyKey(byte[] nonce, String purpose, byte[] sealed) {
        for (String id : order) {
            try {
                return Optional.of(cipher(Cipher.DECRYPT_MODE, byId.get(id), nonce, purpose, sealed));
            } catch (RuntimeException ex) {
                // Not this key.
            }
        }
        return Optional.empty();
    }

    private byte[] nonce() {
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        return nonce;
    }

    private static byte[] cipher(int mode, SecretKeySpec key, byte[] nonce, String purpose, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("AES-GCM failed", ex);
        }
    }

    private static String text(byte[] plain) {
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SecretKeySpec aes(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("An encryption key is 32 bytes");
        }
        return new SecretKeySpec(key.clone(), "AES");
    }

    /** The first four bytes of a SHA-256 of the key, as eight hex characters. */
    static String idOf(byte[] key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("salon key id:".getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(key), 0, ID_BYTES);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
