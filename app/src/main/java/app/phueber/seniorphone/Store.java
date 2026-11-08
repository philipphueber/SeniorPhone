package app.phueber.seniorphone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The app's own settings: the lock flag with its PIN hash, and the colour theme. Contacts and
 * favourites are not stored here: they live in the phone's address book
 * (ContactsContract, "starred"), so they are the same in every app and survive
 * a reinstall.
 */
public final class Store {
    private static final String FILE = "seniorphone";
    private static final String K_LOCKED = "locked";
    private static final String K_SALT = "pin_salt";
    private static final String K_HASH = "pin_hash";
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

    /** The home screen is locked; its PIN unlocks it, here or there. */
    public boolean lockedByLauncher() {
        return LauncherLock.locked(app);
    }

    /** This app's own lock, used when SeniorLauncher is not installed. */
    public synchronized boolean ownLock() {
        return prefs.getBoolean(K_LOCKED, false);
    }

    /** Locks this app with a new PIN. Caller has checked Pin.isValid(pin). */
    public synchronized void lock(String pin) {
        String salt = Pin.newSalt();
        prefs.edit().putString(K_SALT, salt).putString(K_HASH, Pin.hash(salt, pin))
                .putBoolean(K_LOCKED, true).commit();
    }

    /**
     * Lifts every lock that holds this app, the home screen's and its own, if
     * the PIN is right; returns whether nothing is locked any more. An own lock
     * from before PINs existed has no hash; any PIN lifts it.
     */
    public boolean unlock(String pin) {
        if (lockedByLauncher() && !LauncherLock.unlock(app, pin)) return false;
        synchronized (this) {
            if (!prefs.getBoolean(K_LOCKED, false)) return true;
            String hash = prefs.getString(K_HASH, null);
            if (hash != null && !Pin.matches(prefs.getString(K_SALT, null), hash, pin)) return false;
            prefs.edit().putBoolean(K_LOCKED, false).remove(K_SALT).remove(K_HASH).commit();
            return true;
        }
    }

    public synchronized Palette.Theme theme() {
        return Palette.parse(prefs.getString(K_THEME, null));
    }

    public synchronized void setTheme(Palette.Theme theme) {
        prefs.edit().putString(K_THEME, theme.name()).commit();
    }
}
