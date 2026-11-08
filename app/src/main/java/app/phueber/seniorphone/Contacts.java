package app.phueber.seniorphone;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reading and writing the phone's address book (ContactsContract). There is no
 * copy of the contacts in this app: favourites are the address book's
 * "starred" flag, so they are the phone's real favourites, and a contact added
 * here is in every other app too.
 *
 * Every method here does disk I/O and must run on {@link #BACKGROUND}. None of
 * them throws: a revoked permission, a contact deleted meanwhile or a
 * read-only account gives an empty result or false, and the screen says so.
 */
final class Contacts {
    /**
     * One thread for all contact work of the app. One, not a pool, so work runs
     * in the order it was asked for: a delete finishes before the reload that
     * follows it, and a slow list load cannot overtake a newer one. Daemon,
     * because there is nothing to finish when the process goes.
     */
    static final ExecutorService BACKGROUND = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "contacts");
        t.setDaemon(true);
        return t;
    });

    /** For posting results of BACKGROUND work back to the screens. */
    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Contacts() {}

    static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Every contact with at least one phone number, unsorted, without their
     * numbers (the list does not need them). A phone app has nothing to do with
     * an e-mail-only contact, and showing one would be a dead end for a senior.
     */
    static List<ContactInfo> list(ContentResolver cr) {
        List<ContactInfo> out = new ArrayList<>();
        String[] projection = {
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        };
        try (Cursor c = cr.query(ContactsContract.Contacts.CONTENT_URI, projection,
                ContactsContract.Contacts.HAS_PHONE_NUMBER + "=1", null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                out.add(new ContactInfo(c.getLong(0), c.getString(1), c.getString(2),
                        c.getInt(3) != 0, c.getString(4), null));
            }
        } catch (RuntimeException e) {
            // Permission revoked while reading, or a broken provider: show nothing.
        }
        return out;
    }

    /**
     * One contact with its phone numbers, for the contact page, or null when it
     * no longer exists. The photo is the full-size one when there is one (the
     * page shows it big), else the thumbnail. Numbers come primary first, and a
     * number that two accounts both hold is listed once.
     */
    static ContactInfo load(ContentResolver cr, long id) {
        Uri uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id);
        String[] projection = {
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
        };
        try {
            String lookup, name, photo;
            boolean starred;
            try (Cursor c = cr.query(uri, projection, null, null, null)) {
                if (c == null || !c.moveToFirst()) return null;
                lookup = c.getString(0);
                name = c.getString(1);
                starred = c.getInt(2) != 0;
                photo = c.getString(3) != null ? c.getString(3) : c.getString(4);
            }
            return new ContactInfo(id, lookup, name, starred, photo, phones(cr, id));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<ContactInfo.Phone> phones(ContentResolver cr, long id) {
        List<ContactInfo.Phone> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] projection = { Phone._ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL };
        try (Cursor c = cr.query(Phone.CONTENT_URI, projection,
                Phone.CONTACT_ID + "=?", new String[] { String.valueOf(id) },
                Phone.IS_SUPER_PRIMARY + " DESC, " + Phone.IS_PRIMARY + " DESC, " + Phone._ID)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                String number = c.getString(1);
                String key = ContactForm.numberKey(number);
                if (key.isEmpty() || !seen.add(key)) continue;
                out.add(new ContactInfo.Phone(c.getLong(0), number.trim(), c.getInt(2),
                        c.getString(3)));
            }
        }
        return out;
    }

    /** Makes a contact a favourite or not. Needs WRITE_CONTACTS; false when that or the write fails. */
    static boolean setStarred(ContentResolver cr, long id, boolean starred) {
        ContentValues v = new ContentValues();
        v.put(ContactsContract.Contacts.STARRED, starred ? 1 : 0);
        try {
            return cr.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id),
                    v, null, null) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Deletes the person: every raw contact behind the aggregate (the Google
     * copy, the phone copy, ...), as the phone's own contacts app does. Deleting
     * only one copy would leave the contact in the list, which looks as if
     * Delete did nothing.
     */
    static boolean delete(ContentResolver cr, long id) {
        try {
            return cr.delete(RawContacts.CONTENT_URI, RawContacts.CONTACT_ID + "=?",
                    new String[] { String.valueOf(id) }) > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * What the edit form shows and changes: the given and family name and one
     * phone number, with the ids of the rows they came from. -1 means "none
     * yet": a new contact, or an existing one without that row.
     */
    static final class Draft {
        long contactId = -1;
        /** The raw contact new rows are added to. */
        long rawContactId = -1;
        long nameDataId = -1;
        long phoneDataId = -1;
        String first = "";
        String last = "";
        String number = "";
        /** Numbers the form does not show; they are left as they are. */
        int otherNumbers;
    }

    /**
     * The form's starting values for an existing contact, or null when it is
     * gone. A name that only exists as a display name (e.g. from the SIM) is
     * put into "first name" whole, rather than guessing where to split it.
     */
    static Draft loadDraft(ContentResolver cr, long id) {
        Draft d = new Draft();
        d.contactId = id;
        try {
            String[] projection = { Data._ID, Data.RAW_CONTACT_ID, StructuredName.GIVEN_NAME,
                    StructuredName.FAMILY_NAME, StructuredName.DISPLAY_NAME };
            try (Cursor c = cr.query(Data.CONTENT_URI, projection,
                    Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                    new String[] { String.valueOf(id), StructuredName.CONTENT_ITEM_TYPE },
                    Data.IS_SUPER_PRIMARY + " DESC, " + Data._ID)) {
                if (c != null && c.moveToFirst()) {
                    d.nameDataId = c.getLong(0);
                    d.rawContactId = c.getLong(1);
                    d.first = orEmpty(c.getString(2));
                    d.last = orEmpty(c.getString(3));
                    if (d.first.isEmpty() && d.last.isEmpty()) d.first = orEmpty(c.getString(4));
                }
            }
            ContactInfo info = load(cr, id);
            if (info == null) return null;
            if (d.nameDataId < 0) d.first = info.name;
            if (!info.phones.isEmpty()) {
                ContactInfo.Phone p = info.phones.get(0);
                d.phoneDataId = p.dataId;
                d.number = p.number;
                d.otherNumbers = info.phones.size() - 1;
                if (d.rawContactId < 0) d.rawContactId = rawContactOf(cr, Data.CONTENT_URI, p.dataId);
            }
            if (d.rawContactId < 0) d.rawContactId = anyRawContact(cr, id);
            return d.rawContactId < 0 ? null : d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Writes the form in one batch, so a contact is never left half saved.
     * New: a raw contact without an account (the phone's own storage, so it
     * works without a Google account), a name and a mobile number. Existing: the
     * name and number rows are updated in place, keeping the number's type and
     * every field the form does not show. Returns the contact id, or -1.
     */
    static long save(ContentResolver cr, Draft d, String first, String last, String number) {
        String given = ContactForm.cleanName(first);
        String family = ContactForm.cleanName(last);
        String tel = ContactForm.cleanNumber(number);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        try {
            if (d.contactId < 0) {
                ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                        .withValue(RawContacts.ACCOUNT_TYPE, null)
                        .withValue(RawContacts.ACCOUNT_NAME, null)
                        .build());
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(StructuredName.GIVEN_NAME, given)
                        .withValue(StructuredName.FAMILY_NAME, family)
                        .build());
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, tel)
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                        .build());
                ContentProviderResult[] r = cr.applyBatch(ContactsContract.AUTHORITY, ops);
                if (r.length == 0 || r[0].uri == null) return -1;
                return contactOfRaw(cr, ContentUris.parseId(r[0].uri));
            }

            if (d.nameDataId >= 0) {
                // Only the parts; the provider then rebuilds the display name from them.
                ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection(Data._ID + "=?", new String[] { String.valueOf(d.nameDataId) })
                        .withValue(StructuredName.GIVEN_NAME, given)
                        .withValue(StructuredName.FAMILY_NAME, family)
                        .build());
            } else {
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, d.rawContactId)
                        .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(StructuredName.GIVEN_NAME, given)
                        .withValue(StructuredName.FAMILY_NAME, family)
                        .build());
            }
            if (d.nameDataId >= 0 && !given.equals(ContactForm.cleanName(d.first))
                    || d.nameDataId >= 0 && !family.equals(ContactForm.cleanName(d.last))) {
                // The same person from another account has its own name row, and the
                // provider may pick that one as the display name: change those too.
                for (long sibling : siblingRows(cr, d.contactId, StructuredName.CONTENT_ITEM_TYPE,
                        null, d.nameDataId)) {
                    ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                            .withSelection(Data._ID + "=?", new String[] { String.valueOf(sibling) })
                            .withValue(StructuredName.GIVEN_NAME, given)
                            .withValue(StructuredName.FAMILY_NAME, family)
                            .build());
                }
            }
            if (d.phoneDataId >= 0) {
                ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                        .withSelection(Data._ID + "=?", new String[] { String.valueOf(d.phoneDataId) })
                        .withValue(Phone.NUMBER, tel)
                        .build());
                String oldKey = ContactForm.numberKey(d.number);
                if (!oldKey.equals(ContactForm.numberKey(tel))) {
                    // phones() lists a number held by two accounts once; the other copy
                    // would otherwise show up as a second, old number.
                    for (long sibling : siblingRows(cr, d.contactId, Phone.CONTENT_ITEM_TYPE,
                            oldKey, d.phoneDataId)) {
                        ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                                .withSelection(Data._ID + "=?", new String[] { String.valueOf(sibling) })
                                .withValue(Phone.NUMBER, tel)
                                .build());
                    }
                }
            } else {
                ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                        .withValue(Data.RAW_CONTACT_ID, d.rawContactId)
                        .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, tel)
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                        .build());
            }
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            // A changed name can make the provider re-aggregate; follow the raw contact.
            long now = contactOfRaw(cr, d.rawContactId);
            return now >= 0 ? now : d.contactId;
        } catch (Exception e) {
            // RemoteException, OperationApplicationException (e.g. a read-only
            // account), SecurityException: not saved, the form says so.
            return -1;
        }
    }

    /**
     * Data row ids of the given kind across all raw contacts of the person,
     * except one. With a numberKey only phone rows holding that number. Queried
     * first and updated by _ID, because Data updates do not reliably accept
     * view columns such as CONTACT_ID in their selection.
     */
    private static List<Long> siblingRows(ContentResolver cr, long contactId, String mime,
            String numberKey, long except) {
        List<Long> out = new ArrayList<>();
        try (Cursor c = cr.query(Data.CONTENT_URI, new String[] { Data._ID, Data.DATA1 },
                Data.CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                new String[] { String.valueOf(contactId), mime }, Data._ID)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                if (id == except) continue;
                if (numberKey != null && !numberKey.equals(ContactForm.numberKey(c.getString(1)))) continue;
                out.add(id);
            }
        }
        return out;
    }

    private static long contactOfRaw(ContentResolver cr, long rawId) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
                new String[] { RawContacts.CONTACT_ID }, null, null, null)) {
            return c != null && c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : -1;
        }
    }

    private static long rawContactOf(ContentResolver cr, Uri table, long dataId) {
        try (Cursor c = cr.query(ContentUris.withAppendedId(table, dataId),
                new String[] { Data.RAW_CONTACT_ID }, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    private static long anyRawContact(ContentResolver cr, long contactId) {
        try (Cursor c = cr.query(RawContacts.CONTENT_URI, new String[] { RawContacts._ID },
                RawContacts.CONTACT_ID + "=? AND " + RawContacts.DELETED + "=0",
                new String[] { String.valueOf(contactId) }, RawContacts._ID)) {
            return c != null && c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    /**
     * A contact photo, decoded off the main thread and scaled down to about
     * sizePx (full-size contact photos can be 720px or more, far bigger than
     * the avatar). Null when there is none or it cannot be read.
     */
    static Bitmap photo(ContentResolver cr, String uri, int sizePx) {
        if (uri == null) return null;
        Uri u = Uri.parse(uri);
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(u)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (Math.min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2;
            opts.inSampleSize = sample;
            try (InputStream in = cr.openInputStream(u)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
