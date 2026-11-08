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

    private static final String PREFS = "launcher_lock";
    private static final String K_LAST = "last";

    /**
     * True if SeniorLauncher says it is locked. When it cannot be asked (null or
     * empty cursor, exception: its process is dying or being updated) the last
     * answer it gave is used, so the lock does not drop for that moment; stored
     * in prefs so a restart of this app in that window does not lose it. Only
     * while the launcher is still installed: without it nobody could ever
     * unlock, so then false.
     */
    static boolean locked(Context c) {
        try (Cursor cur = c.getContentResolver().query(STATE, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                boolean now = cur.getInt(0) == 1;
                remember(c, now);
                return now;
            }
        } catch (RuntimeException e) {
            // fall through: unknown
        }
        if (!present(c)) return false;
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_LAST, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void remember(Context c, boolean locked) {
        try {
            android.content.SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean(K_LAST, false) != locked) p.edit().putBoolean(K_LAST, locked).apply();
        } catch (RuntimeException ignored) {
            // the cache is a convenience
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
