package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DialNumberTest {

    private static String type(String keys) {
        String n = "";
        for (char c : keys.toCharArray()) n = DialNumber.append(n, c);
        return n;
    }

    @Test public void digitsStarAndHashAreAppended() {
        assertEquals("0171*#9", type("0171*#9"));
    }

    @Test public void plusOnlyAtTheStart() {
        assertEquals("+49", type("+49"));
        assertEquals("049", type("0+49"));
        assertEquals("+49", type("++49"));
    }

    @Test public void unknownKeysAreIgnored() {
        assertEquals("12", type("1a 2-"));
        assertEquals("5", DialNumber.append(null, '5'));
    }

    @Test public void stopsAtMaxLength() {
        StringBuilder keys = new StringBuilder();
        for (int i = 0; i < DialNumber.MAX_LENGTH + 5; i++) keys.append('7');
        assertEquals(DialNumber.MAX_LENGTH, type(keys.toString()).length());
    }

    @Test public void deleteRemovesOneCharacter() {
        assertEquals("017", DialNumber.deleteLast("0171"));
        assertEquals("", DialNumber.deleteLast("+"));
        assertEquals("", DialNumber.deleteLast(""));
        assertEquals("", DialNumber.deleteLast(null));
    }

    @Test public void cleanDropsSeparators() {
        assertEquals("+4917112345", DialNumber.clean("+49 (171) 123-45"));
        assertEquals("08912345", DialNumber.clean("089/123.45"));
        assertEquals("*21#", DialNumber.clean("*21#"));
    }

    @Test public void cleanKeepsOnlyALeadingPlus() {
        assertEquals("+4930", DialNumber.clean(" +49+30"));
        assertEquals("4930", DialNumber.clean("49+30"));
    }

    @Test public void cleanStopsAtPauseOrWait() {
        assertEquals("0301234", DialNumber.clean("0301234,56"));
        assertEquals("0301234", DialNumber.clean("0301234;56"));
        assertEquals("0301234", DialNumber.clean("0301234p56"));
        assertEquals("0301234", DialNumber.clean("0301234w56"));
    }

    @Test public void cleanHandlesNullAndLength() {
        assertEquals("", DialNumber.clean(null));
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 50; i++) raw.append("1 ");
        assertEquals(DialNumber.MAX_LENGTH, DialNumber.clean(raw.toString()).length());
    }

    @Test public void callNeedsADigit() {
        assertFalse(DialNumber.canCall(null));
        assertFalse(DialNumber.canCall(""));
        assertFalse(DialNumber.canCall("+"));
        assertFalse(DialNumber.canCall("*#"));
        assertTrue(DialNumber.canCall("1"));
        assertTrue(DialNumber.canCall("*#06#"));
        assertTrue(DialNumber.canCall("+49"));
    }

    @Test public void cleanDropsOptionalTrunkDigitAfterPlus() {
        assertEquals("+4930123", DialNumber.clean("+49(0)30 123"));
        assertEquals("+4144123", DialNumber.clean("+41 (0)44 123"));
        assertEquals("030012", DialNumber.clean("030(0)12"));
    }

    @Test public void lettersAsOnEveryPhone() {
        assertEquals("ABC", DialNumber.letters('2'));
        assertEquals("PQRS", DialNumber.letters('7'));
        assertEquals("WXYZ", DialNumber.letters('9'));
        assertEquals("", DialNumber.letters('1'));
        assertEquals("", DialNumber.letters('0'));
        assertEquals("", DialNumber.letters('#'));
    }

    @Test public void spokenReadsOneCharacterAtATime() {
        assertEquals("plus 4 9 star hash", DialNumber.spoken("+49*#", "plus", "star", "hash"));
        assertEquals("", DialNumber.spoken("", "plus", "star", "hash"));
        assertEquals("", DialNumber.spoken(null, "plus", "star", "hash"));
    }

    @Test public void everyKeyOnThePadIsAKey() {
        for (char c : "0123456789*#+".toCharArray()) assertTrue(String.valueOf(c), DialNumber.isKey(c));
        for (char c : " -()pwx,;".toCharArray()) assertFalse(String.valueOf(c), DialNumber.isKey(c));
    }
}
