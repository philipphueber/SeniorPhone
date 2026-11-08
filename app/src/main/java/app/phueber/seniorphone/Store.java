package app.phueber.seniorphone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The app's own settings: the lock flag and the colour theme. Contacts and
 * favourites are not stored here: they live in the phone's address book
 * (ContactsContract, "starred"), so they are the same in every app and survive
 * a reinstall.
 */
public final class Store {
    private static final String FILE = "seniorphone";
    private static final String K_LOCKED = "locked";
    private static final String K_THEME = "theme";

    private final SharedPreferences prefs;
    private final Context app;

    public Store(Context context) {
        app = context.getApplicationContext();
        prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Locked: favourites fixed, no editing, deleting or adding contacts. Locked
     * when the helper locked SeniorLauncher's home screen, or (without the
     * launcher) this app's own lock.
     */
    public boolean locked() {
        return lockedByLauncher() || ownLock();
    }

    /** The home screen is locked; it can only be unlocked there. */
    public boolean lockedByLauncher() {
        return LauncherLock.locked(app);
    }

    /** This app's own lock, used when SeniorLauncher is not installed. */
    public synchronized boolean ownLock() {
        return prefs.getBoolean(K_LOCKED, false);
    }

    public synchronized void setLocked(boolean locked) {
        prefs.edit().putBoolean(K_LOCKED, locked).commit();
    }

    public synchronized Palette.Theme theme() {
        return Palette.parse(prefs.getString(K_THEME, null));
    }

    public synchronized void setTheme(Palette.Theme theme) {
        prefs.edit().putString(K_THEME, theme.name()).commit();
    }
}
