package app.phueber.seniorphone;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The order and grouping of the contact list: favourites first, then everyone
 * else A–Z under a letter header, filtered by the search field. Pure (no
 * android.* imports), so the German rules (Ä files under A, ß matches "ss")
 * are checked by a JVM test.
 */
final class ContactList {
    /** The header that stands for the favourites section; any letter header is one character. */
    static final String FAVOURITES = "favourites";
    /** The header for names that do not start with a letter ("+49 170 …", "(Arzt)"). */
    static final String OTHER = "#";

    /** One line of the list: either a header or a contact. */
    static final class Item {
        /** FAVOURITES, OTHER or a single upper-case letter; null for a contact. */
        final String header;
        final ContactInfo contact;

        private Item(String header, ContactInfo contact) {
            this.header = header;
            this.contact = contact;
        }

        static Item header(String h) {
            return new Item(h, null);
        }

        static Item contact(ContactInfo c) {
            return new Item(null, c);
        }

        boolean isHeader() {
            return header != null;
        }
    }

    private ContactList() {}

    /**
     * Sorts in place: names starting with a letter first, in the order of the
     * collator (the phone's language, so "Ärzte" sits among the A's in German),
     * then the rest under "#". The phone's own contacts app also puts "#" last;
     * the collator alone would put digits first, above "Anna".
     */
    static void sort(List<ContactInfo> contacts, Collator collator) {
        contacts.sort(comparator(collator));
    }

    static Comparator<ContactInfo> comparator(Collator collator) {
        return (a, b) -> {
            boolean la = !OTHER.equals(letter(a.name));
            boolean lb = !OTHER.equals(letter(b.name));
            if (la != lb) return la ? -1 : 1;
            int c = collator.compare(a.name, b.name);
            return c != 0 ? c : Long.compare(a.id, b.id); // stable for equal names
        };
    }

    /**
     * The rows to show for already sorted contacts: a "favourites" header and
     * the starred ones, then a header per letter and the others. A favourite
     * appears only once, at the top, as in the reference launcher. Headers of
     * empty sections are left out, so a search shows only what matched.
     */
    static List<Item> items(List<ContactInfo> sorted, String query) {
        String q = searchKey(query == null ? "" : query.trim());
        List<Item> out = new ArrayList<>();
        boolean any = false;
        for (ContactInfo c : sorted) {
            if (!c.starred || !matches(c, q)) continue;
            if (!any) out.add(Item.header(FAVOURITES));
            any = true;
            out.add(Item.contact(c));
        }
        String section = null;
        for (ContactInfo c : sorted) {
            if (c.starred || !matches(c, q)) continue;
            String l = letter(c.name);
            if (!l.equals(section)) {
                out.add(Item.header(l));
                section = l;
            }
            out.add(Item.contact(c));
        }
        return out;
    }

    /** Whether a contact matches an already normalised query (empty matches all). */
    static boolean matches(ContactInfo c, String key) {
        return key.isEmpty() || searchKey(c.name).contains(key);
    }

    /**
     * The section letter of a name: its first letter without accents, upper
     * case ("Ärzte" → "A", "émile" → "E"), or "#" when it does not start with
     * a letter.
     */
    static String letter(String name) {
        if (name == null) return OTHER;
        String s = name.trim();
        if (s.isEmpty()) return OTHER;
        int cp = s.codePointAt(0);
        if (!Character.isLetter(cp)) return OTHER;
        String bare = searchKey(new String(Character.toChars(cp)));
        if (bare.isEmpty()) return OTHER;
        // "ß".toUpperCase() is "SS"; one character is enough for a header.
        String up = bare.toUpperCase(Locale.ROOT);
        return up.substring(0, Character.charCount(up.codePointAt(0)));
    }

    /**
     * The big letter on a contact's round avatar: the first letter of the name,
     * keeping its accent ("Ömer" → "Ö", unlike the section header), or "" when
     * the name does not start with a letter, so the avatar shows a person
     * symbol instead of a "+" or a digit.
     */
    static String initial(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.isEmpty()) return "";
        int cp = s.codePointAt(0);
        if (!Character.isLetter(cp)) return "";
        if (cp == 'ß') return "ß"; // would become "SS"
        return new String(Character.toChars(cp)).toUpperCase(Locale.getDefault());
    }

    /** Lower case without accents, so "arzt" finds "Ärzte" and "strasse" finds "Straße". */
    static String searchKey(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace("ß", "ss");
    }

    /**
     * A stable colour slot for a contact in the COLOURFUL theme, from its id:
     * the same person keeps the same colour from one day to the next, however
     * the list is filtered. Neighbouring ids get neighbouring colours.
     */
    static int colorIndex(long id, int count) {
        return Math.floorMod(Long.hashCode(id), count);
    }
}
