package app.phueber.seniorphone;

import android.app.Activity;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

/**
 * Becoming the phone's default phone app (role DIALER). Only the default phone
 * app gets the calls (CallService) and may show its own big call screens, so
 * the helper is asked once when the app starts, and Settings shows the state.
 *
 * Android itself shows the final "Set as default?" question; we only explain
 * it first and then hand over. Never asked in a loop: at most once per app
 * start, and not at all once the helper has said no (Android 10+ also stops
 * showing its own question after it was declined twice; Settings then opens
 * the default-apps page instead).
 *
 * Note: Palette has a nested enum also called Role; inside Palette (and files
 * that import Palette.Role) that one wins, everywhere else this class does.
 */
public final class Role {
    /** startActivityForResult code of the request; activities may check it in onActivityResult. */
    public static final int REQUEST_CODE = 7301;

    /** Set once the explanation was shown in this process: one question per app start. */
    private static boolean askedThisRun;

    private Role() {}

    /** Whether this app is the default phone app right now. */
    @SuppressWarnings("deprecation")
    public static boolean isDefault(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = c.getSystemService(RoleManager.class);
                return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)
                        && rm.isRoleHeld(RoleManager.ROLE_DIALER);
            }
            TelecomManager tm = c.getSystemService(TelecomManager.class);
            return tm != null && c.getPackageName().equals(tm.getDefaultDialerPackage());
        } catch (RuntimeException e) {
            return false; // a vendor quirk must not take the contact list down
        }
    }

    /** Whether this device has a phone app to be at all (false e.g. on a Wi-Fi-only tablet). */
    public static boolean isAvailable(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = c.getSystemService(RoleManager.class);
                return rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER);
            }
            return c.getSystemService(TelecomManager.class) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Call in the main screen's onCreate. If this is not yet the phone app,
     * explains why it should be and, on "yes", asks Android. Does nothing
     * after the first time in this app start, so recreating the screen or
     * coming back to it never asks again. Returns the shown dialog (to dismiss
     * in onDestroy) or null.
     */
    public static Dialog askIfNeeded(Activity a) {
        if (askedThisRun || a.isFinishing() || isDefault(a) || !isAvailable(a)) return null;
        askedThisRun = true;
        SpannableStringBuilder q = new SpannableStringBuilder(a.getString(R.string.setup_role_question));
        int start = q.length();
        q.append("\n\n").append(a.getString(R.string.setup_role_explain));
        q.setSpan(new RelativeSizeSpan(0.8f), start, q.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return Ui.confirm(a, q, a.getString(R.string.setup_role_yes), R.color.green, () -> request(a));
    }

    /**
     * Shows Android's own "make this the default phone app?" question. The
     * result arrives in a.onActivityResult with REQUEST_CODE. Returns false
     * if the phone has no such question (then openDefaultAppsSettings helps).
     */
    @SuppressWarnings("deprecation") // ACTION_CHANGE_DEFAULT_DIALER: the only way on Android 8 and 9
    public static boolean request(Activity a) {
        askedThisRun = true;
        Intent i;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager rm = a.getSystemService(RoleManager.class);
                if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_DIALER)) return false;
                i = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
            } else {
                i = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME,
                                a.getPackageName());
            }
            a.startActivityForResult(i, REQUEST_CODE);
            return true;
        } catch (RuntimeException e) {
            return false; // no such screen on this phone
        }
    }

    /**
     * The phone's "Default apps" page, where the helper can pick the phone app
     * by hand: the way out when Android no longer shows its own question.
     */
    public static void openDefaultAppsSettings(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (RuntimeException e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                // Nothing left to open.
            }
        }
    }
}
