package app.phueber.seniorphone;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

/**
 * The lock of SeniorLauncher, read through its read-only LockProvider: when the
 * helper locks the home screen, this app is locked too (no new contacts, no
 * changes to favourites), with one long press for the whole phone.
 *
 * Only read, never written: unlocking is done on the home screen. Without
 * SeniorLauncher installed this app keeps its own lock (Store).
 */
final class LauncherLock {
    static final String PACKAGE = "app.phueber.seniorlauncher";
    static final Uri STATE = Uri.parse("content://app.phueber.seniorlauncher.lock/state");

    private LauncherLock() {}

    /** SeniorLauncher is installed and new enough to share its lock. */
    static boolean present(Context c) {
        try {
            return c.getPackageManager().resolveContentProvider(STATE.getAuthority(), 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True if SeniorLauncher says it is locked; false if it is unlocked, absent or silent. */
    static boolean locked(Context c) {
        try (Cursor cur = c.getContentResolver().query(STATE, null, null, null, null)) {
            return cur != null && cur.moveToFirst() && cur.getInt(0) == 1;
        } catch (RuntimeException e) {
            return false; // launcher gone, or crashing: fall back to our own lock
        }
    }

    /** Whether the launcher app itself is installed at all (for the Settings text). */
    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }
}
