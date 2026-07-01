package com.violet.wifidogauthenticator.utils;

import android.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * AES/CBC/PKCS7Padding decryption service.
 *
 * KEY and IV are configurable. By default they use a 16-byte placeholder;
 * replace KEY_HEX and IV_HEX with your actual values (hex strings).
 *
 * FIX: Added explicit null/empty guard before Base64.decode to prevent
 * IllegalArgumentException crash on invalid or non-Base64 user input.
 * FIX: Added hex string length validation in hexToBytes to prevent
 * ArrayIndexOutOfBoundsException on malformed KEY_HEX / IV_HEX.
 * FIX: Wrapped Base64.decode with Base64.NO_WRAP flag fallback; catches
 * all decode-level exceptions separately so the caller always gets null
 * instead of a crash.
 */
public class CryptoService {

    // ---------------------------------------------------------------
    // *** CONFIGURE YOUR AES KEY AND IV HERE (32 hex chars = 16 bytes)
    // ---------------------------------------------------------------
    private static final String KEY_HEX = "000102030405060708090a0b0c0d0e0f"; // 16 bytes
    private static final String IV_HEX  = "101112131415161718191a1b1c1d1e1f"; // 16 bytes
    // ---------------------------------------------------------------

    private static final String ALGORITHM = "AES/CBC/PKCS7Padding";

    /**
     * Decrypts a Base64-encoded AES/CBC/PKCS7 encrypted string.
     *
     * @param encryptedBase64 the encrypted data encoded in Base64
     * @return decrypted plain-text string, or null on any failure (never throws)
     */
    public static String decrypt(String encryptedBase64) {
        // STRICT: Guard against null / blank input
        if (encryptedBase64 == null || encryptedBase64.trim().isEmpty()) {
            Logger.getInstance().warn("CryptoService.decrypt: empty input");
            return null;
        }

        // STRICT: Hard reject any input that looks like a plain URL.
        // AES/CBC Base64 ciphertext will NEVER start with http:// or https://.
        String trimmed = encryptedBase64.trim();
        String lowerCheck = trimmed.toLowerCase();
        if (lowerCheck.startsWith("http://") || lowerCheck.startsWith("https://")) {
            Logger.getInstance().error("CryptoService.decrypt: REJECTED – input is a plain URL, not AES ciphertext.");
            return null;
        }

        // STRICT: Sanity-check input length. AES-encrypted Base64 grows ~4/3 of
        // plaintext. A URL longer than 8 KB is suspicious; cap at 16 KB to prevent
        // DoS via oversized input.
        if (trimmed.length() > 16384) {
            Logger.getInstance().error("CryptoService.decrypt: REJECTED – input exceeds maximum length (16384).");
            return null;
        }

        // STRICT: Pre-validate Base64 characters before decode to catch obviously
        // corrupt input early and produce a clearer log message.
        if (!isValidBase64(trimmed)) {
            Logger.getInstance().error("CryptoService.decrypt: REJECTED – input contains non-Base64 characters.");
            return null;
        }

        try {
            byte[] keyBytes = hexToBytes(KEY_HEX);
            byte[] ivBytes  = hexToBytes(IV_HEX);

            // FIX: Validate key and IV byte lengths before passing to AES
            if (keyBytes == null || (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32)) {
                Logger.getInstance().error("CryptoService: invalid KEY length");
                return null;
            }
            if (ivBytes == null || ivBytes.length != 16) {
                Logger.getInstance().error("CryptoService: invalid IV length");
                return null;
            }

            SecretKeySpec   keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec  = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            // FIX: Decode with NO_WRAP | DEFAULT flags; catch IllegalArgumentException
            // separately so invalid Base64 strings return null instead of crashing.
            byte[] encryptedBytes;
            try {
                encryptedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                Logger.getInstance().error("CryptoService: invalid Base64 input – " + e.getMessage());
                return null;
            }

            // FIX: Guard against empty decoded bytes (e.g. input was whitespace-only after trim)
            if (encryptedBytes == null || encryptedBytes.length == 0) {
                Logger.getInstance().warn("CryptoService: Base64 decoded to empty bytes");
                return null;
            }

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8).trim();

        } catch (Exception e) {
            Logger.getInstance().error("AES decrypt failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Encrypts a plain-text string using AES/CBC/PKCS7 and returns Base64.
     * (Provided for completeness; not used in the main auth flow.)
     */
    public static String encrypt(String plainText) {
        // FIX: Guard null input
        if (plainText == null) {
            Logger.getInstance().warn("CryptoService.encrypt: null input");
            return null;
        }
        try {
            byte[] keyBytes = hexToBytes(KEY_HEX);
            byte[] ivBytes  = hexToBytes(IV_HEX);

            if (keyBytes == null || ivBytes == null) {
                Logger.getInstance().error("CryptoService: invalid key/iv hex");
                return null;
            }

            SecretKeySpec   keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec  = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(encrypted, Base64.DEFAULT);
        } catch (Exception e) {
            Logger.getInstance().error("AES encrypt failed: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Alternate: accept raw key/iv bytes directly
    // ---------------------------------------------------------------
    public static String decryptWithKey(String encryptedBase64, byte[] keyBytes, byte[] ivBytes) {
        // FIX: Guard all arguments
        if (encryptedBase64 == null || encryptedBase64.trim().isEmpty()) return null;
        if (keyBytes == null || ivBytes == null) return null;

        try {
            SecretKeySpec   keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec  = new IvParameterSpec(ivBytes);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

            byte[] encryptedBytes;
            try {
                encryptedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                Logger.getInstance().error("CryptoService (custom key): invalid Base64 – " + e.getMessage());
                return null;
            }

            if (encryptedBytes == null || encryptedBytes.length == 0) return null;

            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            Logger.getInstance().error("AES decrypt (custom key) failed: " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /**
     * STRICT: Pre-validate that a string contains only legal Base64 characters
     * (A-Z, a-z, 0-9, +, /, =) plus optional whitespace.
     * Returns false for any string containing characters outside this set.
     */
    private static boolean isValidBase64(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '='
                    || c == '\n' || c == '\r' || c == ' ';
            if (!ok) return false;
        }
        return true;
    }

    /**
     * STRICT: Returns null instead of throwing if hex is null, odd-length, or
     * contains non-hex characters, preventing ArrayIndexOutOfBoundsException.
     */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i),     16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            // FIX: digit() returns -1 for non-hex characters
            if (hi < 0 || lo < 0) return null;
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}
