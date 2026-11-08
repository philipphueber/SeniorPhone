package app.phueber.seniorphone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * The lock PIN. Stored only as salt + SHA-256 hash. This keeps a curious senior
 * (or a grandchild) from changing contacts and favourites; it is not meant to resist an attacker
 * with root, who could clear the app's data anyway. Pure Java, unit tested.
 */
public final class Pin {
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 8;

    private Pin() {}

    /** True when the PIN has 4 to 8 characters and every one of them is a digit 0-9. */
    public static boolean isValid(String pin) {
        if (pin == null || pin.length() < MIN_LENGTH || pin.length() > MAX_LENGTH) return false;
        for (int i = 0; i < pin.length(); i++) {
            char c = pin.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public static String newSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return hex(b);
    }

    public static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String salt, String expectedHash, String pin) {
        if (salt == null || expectedHash == null || pin == null) return false;
        return MessageDigest.isEqual(
                hash(salt, pin).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
