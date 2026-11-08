package app.phueber.seniorphone;

/**
 * The rules of the "New contact" / "Edit contact" form, and how phone numbers
 * are compared. Pure (no android.* imports), so they are checked by a JVM test.
 */
final class ContactForm {
    /** What is wrong with the form, if anything; the screen shows one sentence per case. */
    enum Problem { NONE, NO_NAME, NO_NUMBER, BAD_NUMBER }

    /** Longer than any real number with extension; stops a pasted paragraph. */
    static final int MAX_NUMBER = 40;

    private ContactForm() {}

    /**
     * A contact needs a name and a number. Without a number it would not be
     * listed at all (the list shows only contacts one can call), so the
     * helper would think saving had failed.
     */
    static Problem check(String first, String last, String number) {
        if (blank(first) && blank(last)) return Problem.NO_NAME;
        String n = cleanNumber(number);
        if (n.isEmpty()) return Problem.NO_NUMBER;
        return validNumber(n) ? Problem.NONE : Problem.BAD_NUMBER;
    }

    /**
     * Digits, a leading "+", and what people type between digit groups
     * (spaces, "-", "/", ".", brackets), plus "*", "#", and "," / ";" (dial
     * pauses for extensions). At least one digit, at most MAX_NUMBER characters.
     */
    static boolean validNumber(String n) {
        if (n.isEmpty() || n.length() > MAX_NUMBER) return false;
        boolean digit = false;
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digit = true;
            } else if (ch == '+') {
                if (i != 0) return false;
            } else if (" -/.()*#,;".indexOf(ch) < 0) {
                return false;
            }
        }
        return digit;
    }

    /** Trimmed, with runs of white space (also tabs, line breaks from a paste) made one space. */
    static String cleanNumber(String number) {
        if (number == null) return "";
        return number.trim().replaceAll("\\s+", " ");
    }

    /** Trimmed name part, or null when empty, so the address book builds the display name from what is there. */
    static String cleanName(String part) {
        if (part == null) return null;
        String s = part.trim().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    /**
     * What makes two numbers "the same" on a contact page: only "+" and digits
     * count, so "0170 123 45" and "0170/12345" are one number. A contact synced
     * from two accounts often carries each number twice; the page shows it once.
     */
    static String numberKey(String number) {
        if (number == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < number.length(); i++) {
            char ch = number.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '+') b.append(ch);
        }
        return b.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
