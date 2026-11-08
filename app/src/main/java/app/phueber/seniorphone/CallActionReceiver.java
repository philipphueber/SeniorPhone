package app.phueber.seniorphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The notification buttons that need no screen: Decline, Hang up, and putting
 * a swiped-away call notification back. (Answer opens CallActivity instead,
 * because it must show the call.) Runs on the main thread, like CallService.
 *
 * A button of a call that no longer exists does nothing. If no call service
 * is alive at all, nothing of ours may still be on screen, so any leftover
 * call notification is removed.
 */
public final class CallActionReceiver extends BroadcastReceiver {
    static final String ACTION_DECLINE = "app.phueber.seniorphone.CALL_DECLINE";
    static final String ACTION_HANG_UP = "app.phueber.seniorphone.CALL_HANG_UP";
    static final String ACTION_REPOST = "app.phueber.seniorphone.CALL_REPOST";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent != null ? intent.getAction() : null;
            int key = intent != null ? intent.getIntExtra(CallActivity.EXTRA_KEY, 0) : 0;
            if (!CallService.hasLiveCall()) {
                CallNotifier.cancelAll(context);
                return;
            }
            if (ACTION_DECLINE.equals(action)) CallService.decline(key);
            else if (ACTION_HANG_UP.equals(action)) CallService.hangUp(key);
            else if (ACTION_REPOST.equals(action)) CallService.repostNotifications();
        } catch (Throwable t) {
            android.util.Log.e("CallActionReceiver", "action", t);
        }
    }
}
