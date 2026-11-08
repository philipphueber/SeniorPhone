package app.phueber.seniorphone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public class ContactListTest {
    private static final Collator GERMAN = Collator.getInstance(Locale.GERMAN);

    private static ContactInfo c(long id, String name, boolean starred) {
        return new ContactInfo(id, "k" + id, name, starred, null, null);
    }

    private static List<String> names(List<ContactInfo> list) {
        List<String> out = new ArrayList<>();
        for (ContactInfo i : list) out.add(i.name);
        return out;
    }

    /** Headers as "[A]", contacts by name, so a whole list reads as one string. */
    private static String render(List<ContactList.Item> items) {
        StringBuilder b = new StringBuilder();
        for (ContactList.Item i : items) {
            if (b.length() > 0) b.append(' ');
            b.append(i.isHeader() ? "[" + i.header + "]" : i.contact.name);
        }
        return b.toString();
    }

    @Test
    public void umlautsSortWithTheirLetterInGerman() {
        List<ContactInfo> l = new ArrayList<>(Arrays.asList(
                c(1, "Bernd", false), c(2, "Ärztehaus", false), c(3, "anna", false),
                c(4, "Zoe", false), c(5, "Özil", false), c(6, "Otto", false)));
        ContactList.sort(l, GERMAN);
        assertEquals(Arrays.asList("anna", "Ärztehaus", "Bernd", "Otto", "Özil", "Zoe"), names(l));
    }

    @Test
    public void namesWithoutALetterGoLast() {
        List<ContactInfo> l = new ArrayList<>(Arrays.asList(
                c(1, "+49 170 1234", false), c(2, "Zoe", false), c(3, "112 Notruf", false),
                c(4, "Anna", false)));
        ContactList.sort(l, GERMAN);
        // Among themselves the collator decides ("+" vs digits differs between JVM and Android).
        assertEquals(Arrays.asList("Anna", "Zoe"), names(l).subList(0, 2));
        assertTrue(names(l).subList(2, 4).containsAll(Arrays.asList("112 Notruf", "+49 170 1234")));
    }

    @Test
    public void equalNamesKeepAStableOrder() {
        List<ContactInfo> l = new ArrayList<>(Arrays.asList(c(9, "Anna", false), c(2, "Anna", false)));
        ContactList.sort(l, GERMAN);
        assertEquals(2, l.get(0).id);
    }

    @Test
    public void favouritesFirstThenLettersAndFavouritesOnlyOnce() {
        List<ContactInfo> l = new ArrayList<>(Arrays.asList(
                c(1, "Anna", false), c(2, "Ärztehaus", false), c(3, "Bernd", true),
                c(4, "Maria Schmidt", true), c(5, "Carla", false), c(6, "0800 Hotline", false)));
        ContactList.sort(l, GERMAN);
        assertEquals("[favourites] Bernd Maria Schmidt [A] Anna Ärztehaus [C] Carla [#] 0800 Hotline",
                render(ContactList.items(l, "")));
    }

    @Test
    public void noFavouritesNoFavouritesHeader() {
        List<ContactInfo> l = new ArrayList<>(Arrays.asList(c(1, "Anna", false)));
        assertEquals("[A] Anna", render(ContactList.items(l, null)));
    }

    @Test
    public void emptyListGivesNoRows() {
        assertTrue(ContactList.items(new ArrayList<>(), "").isEmpty());
    }

    @Test
    public void searchIgnoresCaseAccentsAndEszett() {
        List<ContactInfo> l = new ArrayList<>(Arrays.asList(
                c(1, "Ärztehaus", false), c(2, "Maria Straße", true), c(3, "Bernd", false),
                c(4, "Café Müller", false)));
        ContactList.sort(l, GERMAN);
        assertEquals("[A] Ärztehaus", render(ContactList.items(l, "ARZT")));
        assertEquals("[favourites] Maria Straße", render(ContactList.items(l, "strasse")));
        assertEquals("[C] Café Müller", render(ContactList.items(l, " muller ")));
        assertEquals("", render(ContactList.items(l, "xyz")));
    }

    @Test
    public void letterStripsAccentsAndFallsBackToHash() {
        assertEquals("A", ContactList.letter("Ärztehaus"));
        assertEquals("E", ContactList.letter("émile"));
        assertEquals("O", ContactList.letter(" Özil"));
        assertEquals("S", ContactList.letter("ßtest"));
        assertEquals("#", ContactList.letter("+49 170"));
        assertEquals("#", ContactList.letter("(Arzt)"));
        assertEquals("#", ContactList.letter(""));
        assertEquals("#", ContactList.letter(null));
    }

    @Test
    public void initialKeepsAccentsAndIsEmptyWithoutALetter() {
        assertEquals("Ö", ContactList.initial("özil"));
        assertEquals("M", ContactList.initial("Maria Schmidt"));
        assertEquals("", ContactList.initial("+49 170 1234"));
        assertEquals("", ContactList.initial("  "));
        assertEquals("", ContactList.initial(null));
    }

    @Test
    public void colourIndexIsStableAndInRange() {
        int n = 8;
        for (long id : new long[] { 0, 1, 7, 8, 123456789012L, -5, Long.MIN_VALUE, Long.MAX_VALUE }) {
            int i = ContactList.colorIndex(id, n);
            assertTrue(i >= 0 && i < n);
            assertEquals(i, ContactList.colorIndex(id, n));
        }
        assertFalse(ContactList.colorIndex(1, n) == ContactList.colorIndex(2, n));
    }

    @Test
    public void contactInfoNormalisesName() {
        ContactInfo i = new ContactInfo(1, null, null, false, null, null);
        assertEquals("", i.name);
        assertTrue(i.phones.isEmpty());
        assertNull(i.photoUri);
        assertEquals("Anna", new ContactInfo(1, null, "  Anna ", false, null, null).name);
    }
}
