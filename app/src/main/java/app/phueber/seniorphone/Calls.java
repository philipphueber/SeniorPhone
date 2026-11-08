package app.phueber.seniorphone;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.TelecomManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Placing a call. One entry point for every screen (contact page, number pad),
 * so a call always goes the same way: through Telecom, which then hands it to
 * CallService and our own call screen.
 */
public final class Calls {
    private static final String TAG = "Calls";
    /** Request code of the CALL_PHONE request; see onRequestPermissionsResult. */
    public static final int REQUEST_CALL_PHONE = 7311; // Role uses 7301
    /** A number waits this long for the permission answer, then it is forgotten. */
    private static final long PENDING_MS = 60_000;

    private static String pendingNumber;
    private static long pendingAt;

    private Calls() {}

    /**
     * Calls the number. Without CALL_PHONE it asks for it first; if the
     * calling activity forwards onRequestPermissionsResult here, the call then
     * goes ahead by itself. Never throws.
     */
    public static void place(Activity a, String number) {
        try {
            if (number == null || number.trim().isEmpty()) return;
            String n = number.trim();
            if (a.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                pendingNumber = n;
                pendingAt = SystemClock.elapsedRealtime();
                a.requestPermissions(new String[] { Manifest.permission.CALL_PHONE }, REQUEST_CALL_PHONE);
                return;
            }
            TelecomManager tm = a.getSystemService(TelecomManager.class);
            if (tm == null) {
                failed(a);
                return;
            }
            tm.placeCall(Uri.fromParts("tel", n, null), new Bundle());
        } catch (SecurityException e) {
            // Permission revoked between the check and the call: nothing to do.
            Log.w(TAG, "placeCall refused", e);
        } catch (Throwable t) {
            Log.e(TAG, "placeCall", t);
            failed(a);
        }
    }

    /**
     * Optional: call from the activity's onRequestPermissionsResult. If it was
     * our request and the senior allowed calling, the waiting number is called.
     */
    public static void onRequestPermissionsResult(Activity a, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_CALL_PHONE) return;
        String n = pendingNumber;
        boolean fresh = SystemClock.elapsedRealtime() - pendingAt < PENDING_MS;
        pendingNumber = null;
        if (n != null && fresh && grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            place(a, n);
        }
    }

    private static void failed(Activity a) {
        try {
            Toast.makeText(a, R.string.call_failed_to_start, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
            // nothing more we can do
        }
    }
}
