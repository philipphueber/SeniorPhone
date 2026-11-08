package app.phueber.seniorphone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;

/**
 * The call notifications. Two of them, so an ongoing call stays reachable
 * while a second call rings (call waiting):
 *  - INCOMING: full-screen intent (wakes the screen and shows CallActivity
 *    over the lock screen) plus Answer / Decline buttons. If Android 14+
 *    refuses the full-screen intent, it still appears as a heads-up with
 *    those buttons.
 *  - ONGOING: "Call with ..." with Hang up, tap opens the call screen.
 * The channel is silent: Telecom plays the ringtone and vibrates (we do not
 * declare IN_CALL_SERVICE_RINGING), and stops them itself.
 *
 * Main thread only. Nothing here throws: a failed notification must never
 * break the call screen, which CallService opens directly anyway.
 */
final class CallNotifier {
    private static final String TAG = "CallNotifier";
    /** Heads-up + full-screen intent: an incoming call while our screen is not in front. */
    static final String CHANNEL = "calls";
    /** No heads-up: ongoing calls, and an incoming call while our screen already shows it. */
    static final String CHANNEL_QUIET = "call_status";
    private static final int ID_INCOMING = 1;
    private static final int ID_ONGOING = 2;

    /** Last content posted per notification, so repeated Telecom updates do not hit Android's rate limit. */
    private static String postedIncoming, postedOngoing;

    private CallNotifier() {}

    /** Creates both channels; idempotent (the user's later changes to them are kept). */
    static void ensureChannel(Context ctx) {
        create(ctx, CHANNEL, R.string.call_channel, R.string.call_channel_description,
                NotificationManager.IMPORTANCE_HIGH);
        // DEFAULT, not LOW: LOW ("silent") notifications may be hidden on the lock screen.
        create(ctx, CHANNEL_QUIET, R.string.call_channel_quiet, R.string.call_channel_quiet_description,
                NotificationManager.IMPORTANCE_DEFAULT);
    }

    private static void create(Context ctx, String id, int name, int description, int importance) {
        try {
            NotificationChannel ch = new NotificationChannel(id, ctx.getString(name), importance);
            ch.setDescription(ctx.getString(description));
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ctx.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        } catch (Throwable t) {
            Log.e(TAG, "channel " + id, t);
        }
    }

    /**
     * Whether an incoming call can wake the screen through the notification by
     * itself. False if notifications are off, the channel was turned down, or
     * (Android 14+) full-screen intents are denied. The call screen is still
     * opened directly by CallService; this is for a warning in Settings.
     */
    static boolean callScreenCanAppear(Context ctx) {
        try {
            ensureChannel(ctx);
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (!nm.areNotificationsEnabled()) return false;
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
            if (ch != null && ch.getImportance() < NotificationManager.IMPORTANCE_HIGH) return false;
            return Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * loud: heads-up channel with the full-screen intent; else the quiet channel
     * (our call screen is already in front), still with Answer / Decline.
     */
    static void showIncoming(Context ctx, int key, String title, boolean loud, boolean force) {
        String who = title != null ? title : ctx.getString(R.string.call_unknown_number);
        String sig = key + "|" + who + "|" + loud;
        if (!force && sig.equals(postedIncoming)) return;
        try {
            ensureChannel(ctx);
            PendingIntent screen = screenIntent(ctx, key, null, 1);
            Notification.Builder b = new Notification.Builder(ctx, loud ? CHANNEL : CHANNEL_QUIET)
                    .setSmallIcon(R.drawable.ic_call_answer)
                    .setContentTitle(who)
                    .setContentText(ctx.getString(R.string.call_is_calling))
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setShowWhen(false)
                    .setContentIntent(screen)
                    .setDeleteIntent(receiverIntent(ctx, CallActionReceiver.ACTION_REPOST, 0, 2))
                    // Answer first: the app's rule is "positive action first".
                    // Answer opens the call screen (an activity, so no
                    // trampoline), which answers and shows the call.
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_call_answer),
                            ctx.getString(R.string.call_answer),
                            screenIntent(ctx, key, CallActivity.ACTION_ANSWER, 3)).build())
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_call_end),
                            ctx.getString(R.string.call_decline),
                            receiverIntent(ctx, CallActionReceiver.ACTION_DECLINE, key, 4)).build());
            if (loud) b.setFullScreenIntent(screen, true);
            notify(ctx, ID_INCOMING, b.build());
            postedIncoming = sig;
        } catch (Throwable t) {
            Log.e(TAG, "incoming", t);
        }
    }

    static void showOngoing(Context ctx, int key, String title, CallState.Screen screen,
                            long connectedAt, boolean force) {
        String who = title != null ? title : ctx.getString(R.string.call_unknown_number);
        int textRes = screen == CallState.Screen.HOLDING ? R.string.call_on_hold
                : screen == CallState.Screen.ACTIVE ? R.string.call_connected
                : screen == CallState.Screen.CHOOSE_SIM ? R.string.call_choose_sim
                : R.string.call_calling;
        boolean timer = screen == CallState.Screen.ACTIVE && connectedAt > 0;
        String sig = key + "|" + who + "|" + textRes + "|" + (timer ? connectedAt : 0);
        if (!force && sig.equals(postedOngoing)) return;
        try {
            ensureChannel(ctx);
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL_QUIET)
                    .setSmallIcon(R.drawable.ic_call_answer)
                    .setContentTitle(who)
                    .setContentText(ctx.getString(textRes))
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(screenIntent(ctx, key, null, 5))
                    .setDeleteIntent(receiverIntent(ctx, CallActionReceiver.ACTION_REPOST, 0, 6))
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(ctx, R.drawable.ic_call_end),
                            ctx.getString(R.string.call_hang_up),
                            receiverIntent(ctx, CallActionReceiver.ACTION_HANG_UP, key, 7)).build());
            if (timer) {
                b.setWhen(connectedAt).setShowWhen(true).setUsesChronometer(true);
            } else {
                b.setShowWhen(false);
            }
            notify(ctx, ID_ONGOING, b.build());
            postedOngoing = sig;
        } catch (Throwable t) {
            Log.e(TAG, "ongoing", t);
        }
    }

    static void cancelIncoming(Context ctx) {
        if (postedIncoming == null) return;
        cancel(ctx, ID_INCOMING);
        postedIncoming = null;
    }

    static void cancelOngoing(Context ctx) {
        if (postedOngoing == null) return;
        cancel(ctx, ID_ONGOING);
        postedOngoing = null;
    }

    /** Removes both, whatever we think is posted (also after a process restart). */
    static void cancelAll(Context ctx) {
        cancel(ctx, ID_INCOMING);
        cancel(ctx, ID_ONGOING);
        postedIncoming = postedOngoing = null;
    }

    private static void notify(Context ctx, int id, Notification n) {
        // Without POST_NOTIFICATIONS (Android 13+) this is silently dropped by
        // the system; the call screen does not depend on it.
        ctx.getSystemService(NotificationManager.class).notify(id, n);
    }

    private static void cancel(Context ctx, int id) {
        try {
            ctx.getSystemService(NotificationManager.class).cancel(id);
        } catch (Throwable t) {
            Log.e(TAG, "cancel", t);
        }
    }

    /** Distinct request codes per call and purpose keep the PendingIntents apart. */
    private static int requestCode(int key, int slot) {
        return key * 8 + slot;
    }

    private static PendingIntent screenIntent(Context ctx, int key, String action, int slot) {
        Intent i = new Intent(ctx, CallActivity.class)
                .putExtra(CallActivity.EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        if (action != null) i.setAction(action);
        return PendingIntent.getActivity(ctx, requestCode(key, slot), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent receiverIntent(Context ctx, String action, int key, int slot) {
        Intent i = new Intent(ctx, CallActionReceiver.class).setAction(action)
                .putExtra(CallActivity.EXTRA_KEY, key);
        return PendingIntent.getBroadcast(ctx, requestCode(key, slot), i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
