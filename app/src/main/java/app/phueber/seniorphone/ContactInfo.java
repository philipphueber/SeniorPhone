package app.phueber.seniorphone;

import java.util.Collections;
import java.util.List;

/**
 * One contact as this app shows it: a plain value, read once off the main
 * thread by {@link Contacts} and never changed afterwards. Pure (no android.*
 * imports), so the sorting and sectioning in {@link ContactList} can be tested
 * on the JVM.
 *
 * The id is the aggregate contact id of ContactsContract, i.e. one person as
 * the phone's own contacts app shows them, even when several accounts (Google,
 * SIM, the phone itself) each hold a copy.
 */
final class ContactInfo {
    /** One phone number of a contact. */
    static final class Phone {
        /** The Data row's _ID: what EditContactActivity updates. */
        final long dataId;
        final String number;
        /** ContactsContract Phone.TYPE_* ; the screen turns it into "Mobile", "Home", ... */
        final int type;
        /** The user's own label when type is TYPE_CUSTOM, else null. */
        final String label;

        Phone(long dataId, String number, int type, String label) {
            this.dataId = dataId;
            this.number = number == null ? "" : number;
            this.type = type;
            this.label = label;
        }
    }

    final long id;
    final String lookupKey;
    /** Never null; empty when the address book has no name for this contact. */
    final String name;
    final boolean starred;
    /** content:// URI of the photo, or null for none. A string to stay android-free. */
    final String photoUri;
    /** Empty in the list (not needed there); filled for the contact page. */
    final List<Phone> phones;

    ContactInfo(long id, String lookupKey, String name, boolean starred, String photoUri,
                List<Phone> phones) {
        this.id = id;
        this.lookupKey = lookupKey;
        this.name = name == null ? "" : name.trim();
        this.starred = starred;
        this.photoUri = photoUri;
        this.phones = phones == null ? Collections.emptyList()
                : Collections.unmodifiableList(phones);
    }
}
