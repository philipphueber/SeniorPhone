package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ContactFormTest {
    @Test
    public void needsANameAndANumber() {
        assertEquals(ContactForm.Problem.NONE, ContactForm.check("Maria", "", "0170 1234567"));
        assertEquals(ContactForm.Problem.NONE, ContactForm.check(null, "Schmidt", "+49 170 1234567"));
        assertEquals(ContactForm.Problem.NO_NAME, ContactForm.check(" ", null, "0170"));
        assertEquals(ContactForm.Problem.NO_NUMBER, ContactForm.check("Maria", "", "   "));
        assertEquals(ContactForm.Problem.NO_NUMBER, ContactForm.check("Maria", "", null));
        assertEquals(ContactForm.Problem.BAD_NUMBER, ContactForm.check("Maria", "", "Maria"));
    }

    @Test
    public void numbersAllowWhatPeopleType() {
        assertTrue(ContactForm.validNumber("+49 (0)170 / 123-45.67"));
        assertTrue(ContactForm.validNumber("*31#"));
        assertTrue(ContactForm.validNumber("0711 1234,56"));
        assertTrue(ContactForm.validNumber("112"));
    }

    @Test
    public void numbersRejectLettersLatePlusNoDigitsAndTooLong() {
        assertFalse(ContactForm.validNumber("0170 abc"));
        assertFalse(ContactForm.validNumber("0049+170"));
        assertFalse(ContactForm.validNumber("+ - /"));
        assertFalse(ContactForm.validNumber(""));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= ContactForm.MAX_NUMBER; i++) b.append('1');
        assertFalse(ContactForm.validNumber(b.toString()));
        assertTrue(ContactForm.validNumber(b.substring(1)));
    }

    @Test
    public void cleaning() {
        assertEquals("0170 123 45", ContactForm.cleanNumber("  0170\t123\n 45 "));
        assertEquals("", ContactForm.cleanNumber(null));
        assertEquals("Anna Lena", ContactForm.cleanName(" Anna   Lena "));
        assertNull(ContactForm.cleanName("  "));
        assertNull(ContactForm.cleanName(null));
    }

    @Test
    public void numberKeyKeepsOnlyPlusAndDigits() {
        assertEquals(ContactForm.numberKey("0170 123 45"), ContactForm.numberKey("0170/12345"));
        assertEquals("+4917012345", ContactForm.numberKey("+49 (170) 123-45"));
        assertEquals("", ContactForm.numberKey(null));
    }
}
