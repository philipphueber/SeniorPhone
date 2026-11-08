package app.phueber.seniorphone;

import static org.junit.Assert.*;

import org.junit.Test;

public class PinTest {
    @Test public void validPins() {
        assertTrue(Pin.isValid("1234"));
        assertTrue(Pin.isValid("12345678"));
        assertTrue(Pin.isValid("0000"));
    }

    @Test public void invalidPins() {
        assertFalse(Pin.isValid(null));
        assertFalse(Pin.isValid(""));
        assertFalse(Pin.isValid("123"));
        assertFalse(Pin.isValid("123456789"));
        assertFalse(Pin.isValid("12a4"));
        assertFalse(Pin.isValid("abcd"));
        assertFalse(Pin.isValid("12 34"));
        assertFalse(Pin.isValid(" 1234"));
        assertFalse(Pin.isValid("-123"));
        assertFalse(Pin.isValid("١٢٣٤")); // Arabic-Indic digits
        assertFalse(Pin.isValid("１２３４")); // fullwidth digits
    }

    @Test public void hashDeterministicPerSalt() {
        assertEquals(Pin.hash("salt", "1234"), Pin.hash("salt", "1234"));
        assertNotEquals(Pin.hash("salt1", "1234"), Pin.hash("salt2", "1234"));
        assertNotEquals(Pin.hash("salt", "1234"), Pin.hash("salt", "1235"));
        assertEquals(64, Pin.hash("salt", "1234").length());
        assertTrue(Pin.hash("salt", "1234").matches("[0-9a-f]{64}"));
    }

    @Test public void matchesTrueAndFalse() {
        String salt = Pin.newSalt();
        String h = Pin.hash(salt, "4711");
        assertTrue(Pin.matches(salt, h, "4711"));
        assertFalse(Pin.matches(salt, h, "4712"));
        assertFalse(Pin.matches("other", h, "4711"));
        assertFalse(Pin.matches(salt, "deadbeef", "4711"));
    }

    @Test public void nullsNeverMatch() {
        String h = Pin.hash("s", "1234");
        assertFalse(Pin.matches(null, h, "1234"));
        assertFalse(Pin.matches("s", null, "1234"));
        assertFalse(Pin.matches("s", h, null));
        assertFalse(Pin.matches(null, null, null));
    }

    @Test public void everyLengthBoundary() {
        for (int len = 0; len <= 12; len++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) sb.append((char) ('0' + i % 10));
            assertEquals("len " + len, len >= 4 && len <= 8, Pin.isValid(sb.toString()));
        }
    }

    @Test public void everyNonDigitCharInvalidInAnyPosition() {
        for (char ch = 0; ch < 0x300; ch++) {
            if (ch >= '0' && ch <= '9') continue;
            for (int pos = 0; pos < 4; pos++) {
                char[] p = {'1', '2', '3', '4'};
                p[pos] = ch;
                assertFalse("char " + (int) ch + " at " + pos, Pin.isValid(new String(p)));
            }
        }
    }

    @Test public void everyDigitValid() {
        for (char d = '0'; d <= '9'; d++) {
            assertTrue(Pin.isValid("" + d + d + d + d));
        }
    }

    @Test public void lineBreaksAndSurrogatesInvalid() {
        assertFalse(Pin.isValid("1234\n"));
        assertFalse(Pin.isValid("12\n34"));
        assertFalse(Pin.isValid("12\u0000" + "34"));
        assertFalse(Pin.isValid("𝟘𝟙𝟚𝟛")); // mathematical digits
    }

    @Test public void hashIsNotPlainAndDependsOnBothInputs() {
        String h = Pin.hash("a", "1234");
        assertFalse(h.contains("1234"));
        // Separator prevents salt/pin boundary ambiguity.
        assertNotEquals(Pin.hash("a1", "234"), Pin.hash("a", "1234"));
        assertNotEquals(Pin.hash("", "1234"), Pin.hash("1234", ""));
    }

    @Test public void knownVector() {
        // SHA-256("s:1234"), lowercase hex; pins the stored format.
        assertEquals("663e504d0f9c7f45da38f1fc40ca8c18dd1abe4614f49333762cc02ba8b47c97",
                Pin.hash("s", "1234"));
    }

    @Test public void matchesWorksForAllPinsAndRejectsNeighbours() {
        String salt = Pin.newSalt();
        for (int n = 0; n < 10000; n += 37) {
            String pin = String.format("%04d", n);
            String h = Pin.hash(salt, pin);
            assertTrue(Pin.matches(salt, h, pin));
            assertFalse(Pin.matches(salt, h, String.format("%04d", (n + 1) % 10000)));
        }
    }

    @Test public void matchesRejectsMalformedHashes() {
        String salt = "s";
        String h = Pin.hash(salt, "1234");
        assertFalse(Pin.matches(salt, "", "1234"));
        assertFalse(Pin.matches(salt, h.substring(1), "1234"));
        assertFalse(Pin.matches(salt, h + "0", "1234"));
        assertFalse(Pin.matches(salt, h.toUpperCase(), "1234"));
    }

    @Test public void saltsAreUnique() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 500; i++) assertTrue(seen.add(Pin.newSalt()));
    }

    @Test public void newSalt() {
        String a = Pin.newSalt();
        String b = Pin.newSalt();
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
        assertNotEquals(a, b);
    }
}
