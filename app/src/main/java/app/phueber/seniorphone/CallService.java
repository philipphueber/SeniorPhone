package app.phueber.seniorphone;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telecom binds this service while at least one call exists, because we are
 * the default phone app. It is the single owner of call state: the calls,
 * their caller names, the two notifications and the proximity wake lock.
 * CallActivity and CallActionReceiver only read it and send commands to it.
 *
 * RELIABILITY INVARIANT: the senior can always answer, decline and hang up.
 *  - Every call added starts CallActivity directly. A bound InCallService may
 *    start activities from the background (Android's documented exemption), so
 *    this works with notifications denied and full-screen intents refused.
 *  - Incoming calls also get a full-screen-intent notification (wakes the
 *    screen over the lock screen) with Answer / Decline buttons, which stay
 *    usable as a heads-up if Android 14+ refuses the full-screen intent.
 *  - Live non-ringing calls get an ongoing notification with Hang up.
 *  - If our process dies, Telecom rebinds and calls onCallAdded again for each
 *    existing call, so screen and notifications come back.
 *  - The state shown is always read fresh from Telecom's Call objects; an
 *    ended call never keeps buttons (see CallState.screen).
 *  - Every command is safe in any state, any number of times, and never throws.
 * All state lives on the main thread: Telecom calls us there, and the Call
 * callbacks are registered on the main looper.
 */
public final class CallService extends InCallService {
    private static final String TAG = "CallService";

    /** One call we know about. Fields are main-thread only. */
    static final class Entry {
        final Call call;
        /** Our stable id for this call: survives in notification PendingIntents. */
        final int key;
        /** Bigger = added later. */
        final long order;
        /** SystemClock.elapsedRealtime() when Telecom gave us the call. */
        final long addedAt = SystemClock.elapsedRealtime();
        /** The raw number, or null (withheld / unknown). */
        String number;
        String contactName;
        long contactId = -1;
        Bitmap photo;
        /** The number the last lookup was for, so a changed handle is looked up again. */
        String lookedUp;

        Entry(Call call, int key, long order) {
            this.call = call;
            this.key = key;
            this.order = order;
        }

        int state() {
            return stateOf(call);
        }

        CallState.Screen screen() {
            return CallState.screen(state());
        }

        /** The big name: contact, network name, number; null = unknown number. */
        String title() {
            Call.Details d = call.getDetails();
            String network = d != null ? d.getCallerDisplayName() : null;
            return CallState.title(contactName, network, formatted());
        }

        /** The formatted number, or null. */
        String formatted() {
            return number == null ? null : format(number);
        }

        /** Wall-clock time the call connected, or 0. */
        long connectTime() {
            Call.Details d = call.getDetails();
            return d != null ? Math.max(0, d.getConnectTimeMillis()) : 0;
        }

        int disconnectCode() {
            Call.Details d = call.getDetails();
            DisconnectCause c = d != null ? d.getDisconnectCause() : null;
            return c != null ? c.getCode() : CallState.D_UNKNOWN;
        }
    }

    /** What the screen needs after the shown call is gone. */
    static final class Ended {
        final int key;
        final String title;
        final CallState.End end;

        Ended(int key, String title, CallState.End end) {
            this.key = key;
            this.title = title;
            this.end = end;
        }
    }

    /** CallActivity listens for every change; called on the main thread. */
    interface Listener {
        void onCallsChanged();
    }

    private static CallService instance;
    private static final List<Entry> entries = new ArrayList<>();
    private static final List<Listener> listeners = new ArrayList<>();
    private static Ended lastEnded;
    private static int nextKey = 1;
    private static long nextOrder = 1;

    private static final ExecutorService lookups = Executors.newSingleThreadExecutor();
    /**
     * How long an incoming call on an unlocked, switched-on phone waits for the
     * directly started screen before the notification is posted as a heads-up
     * after all. Long enough for the activity to start, short enough that the
     * fallback still comes during the first ring.
     */
    private static final long SCREEN_WAIT_MS = 1200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable recheck = this::changed;
    private PowerManager.WakeLock proximity;

    private final Call.Callback callback = new Call.Callback() {
        @Override public void onStateChanged(Call call, int state) {
            Entry e = find(call);
            if (e != null && !CallState.isLive(state)) rememberEnded(e);
            changed();
        }

        @Override public void onDetailsChanged(Call call, Call.Details details) {
            Entry e = find(call);
            if (e != null) {
                lookUp(e);
                if (!CallState.isLive(e.state())) rememberEnded(e);
            }
            changed();
        }

        @Override public void onCallDestroyed(Call call) {
            changed();
        }
    };

    // ---- Telecom ----------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CallNotifier.ensureChannel(this);
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        try {
            if (find(call) == null) {
                Entry e = new Entry(call, nextKey++, nextOrder++);
                entries.add(e);
                call.registerCallback(callback, main);
                lookUp(e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "onCallAdded", t);
        }
        changed();
        // The screen, directly; see the class comment.
        openScreen(this);
    }

    @Override
    public void onCallRemoved(Call call) {
        try {
            Entry e = find(call);
            if (e != null) {
                rememberEnded(e);
                entries.remove(e);
            }
            call.unregisterCallback(callback);
        } catch (Throwable t) {
            Log.e(TAG, "onCallRemoved", t);
        }
        changed();
        super.onCallRemoved(call);
    }

    @Override
    @SuppressWarnings("deprecation") // onCallEndpointChanged is API 34; this still works there
    public void onCallAudioStateChanged(CallAudioState audioState) {
        changed();
    }

    /** The system asks to show the call screen again (e.g. its "return to call" control). */
    @Override
    public void onBringToForeground(boolean showDialpad) {
        openScreen(this);
    }

    @Override
    public void onDestroy() {
        // Telecom unbinds when no call is left, or our binding died. Nothing we
        // still hold may outlive the calls: buttons, notifications, wake lock.
        try {
            for (Entry e : entries) {
                try { e.call.unregisterCallback(callback); } catch (Throwable t) { Log.e(TAG, "unregister", t); }
            }
            entries.clear();
        } catch (Throwable t) { Log.e(TAG, "clear", t); }
        main.removeCallbacksAndMessages(null);
        setProximity(false);
        CallNotifier.cancelAll(this);
        if (instance == this) instance = null;
        notifyListeners();
        super.onDestroy();
    }

    // ---- Reading state (main thread) --------------------------------------

    /** The call that owns the screen, or null. */
    static Entry primary() {
        int i = CallState.pickPrimary(states(), orders());
        return i < 0 ? null : entries.get(i);
    }

    /** The call shown in the screen's place, if there is one: same as primary(). */
    static Entry current() {
        return primary();
    }

    static Entry byKey(int key) {
        for (Entry e : entries) if (e.key == key) return e;
        return null;
    }

    /** Whether any call is live: e.g. for a "Back to the call" banner on other screens. */
    static boolean hasLiveCall() {
        return primary() != null;
    }

    /** Whether another live call exists besides the given one (call waiting). */
    static boolean hasOtherLiveCall(Entry than) {
        for (Entry e : entries) if (e != than && CallState.isLive(e.state())) return true;
        return false;
    }

    /** The last call that ended, for the "Call ended" screen. */
    static Ended lastEnded() {
        return lastEnded;
    }

    /** The current audio route (CallState.R_*), or 0 if unknown. */
    @SuppressWarnings("deprecation") // CallAudioState: the only route API below Android 14
    static int audioRoute() {
        CallService s = instance;
        CallAudioState a = s != null ? s.getCallAudioState() : null;
        return a != null ? a.getRoute() : 0;
    }

    @SuppressWarnings("deprecation")
    private static int supportedRoutes() {
        CallService s = instance;
        CallAudioState a = s != null ? s.getCallAudioState() : null;
        return a != null ? a.getSupportedRouteMask() : 0;
    }

    static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ---- Commands: safe in any state, never throw -------------------------

    static void answer(int key) {
        try {
            Entry e = byKey(key);
            if (e != null && CallState.isRinging(e.state())) e.call.answer(VideoProfile.STATE_AUDIO_ONLY);
        } catch (Throwable t) { Log.e(TAG, "answer", t); }
    }

    static void decline(int key) {
        try {
            Entry e = byKey(key);
            if (e == null) return;
            if (CallState.isRinging(e.state())) e.call.reject(false, null);
            else if (CallState.isLive(e.state())) e.call.disconnect();
        } catch (Throwable t) { Log.e(TAG, "decline", t); }
    }

    static void hangUp(int key) {
        try {
            Entry e = byKey(key);
            if (e != null && CallState.isLive(e.state())) e.call.disconnect();
        } catch (Throwable t) { Log.e(TAG, "hangUp", t); }
    }

    static void unhold(int key) {
        try {
            Entry e = byKey(key);
            if (e != null && e.state() == CallState.T_HOLDING) e.call.unhold();
        } catch (Throwable t) { Log.e(TAG, "unhold", t); }
    }

    static void chooseSim(int key, PhoneAccountHandle account) {
        try {
            Entry e = byKey(key);
            if (e != null && e.state() == CallState.T_SELECT_PHONE_ACCOUNT) {
                e.call.phoneAccountSelected(account, false);
            }
        } catch (Throwable t) { Log.e(TAG, "chooseSim", t); }
    }

    @SuppressWarnings("deprecation") // setAudioRoute: requestCallEndpointChange is API 34
    static void setSpeaker(boolean on) {
        try {
            CallService s = instance;
            if (s == null) return;
            s.setAudioRoute(on ? CallState.R_SPEAKER : CallState.routeForSpeakerOff(supportedRoutes()));
        } catch (Throwable t) { Log.e(TAG, "speaker", t); }
    }

    /** The SIM cards to offer for a call waiting in CHOOSE_SIM. */
    @SuppressWarnings("deprecation") // getParcelableArrayList(String): the typed one is API 33
    static List<PhoneAccountHandle> simChoices(Context ctx, Entry e) {
        List<PhoneAccountHandle> out = new ArrayList<>();
        try {
            Call.Details d = e.call.getDetails();
            Bundle[] bundles = { d != null ? d.getIntentExtras() : null, d != null ? d.getExtras() : null };
            for (Bundle b : bundles) {
                if (b == null || !out.isEmpty()) continue;
                ArrayList<PhoneAccountHandle> l = b.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS);
                if (l != null) out.addAll(l);
            }
        } catch (Throwable t) { Log.e(TAG, "sim extras", t); }
        if (out.isEmpty() && ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                TelecomManager tm = ctx.getSystemService(TelecomManager.class);
                if (tm != null) out.addAll(tm.getCallCapablePhoneAccounts());
            } catch (Throwable t) { Log.e(TAG, "sim accounts", t); }
        }
        return out;
    }

    /** Opens the call screen. From the service this is allowed in the background. */
    static void openScreen(Context ctx) {
        try {
            ctx.startActivity(new Intent(ctx, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION));
        } catch (Throwable t) {
            // The notification's full-screen intent and its buttons remain.
            Log.w(TAG, "cannot open call screen", t);
        }
    }

    /** Puts the notifications back if they were swiped away (Android 14+ allows it). */
    static void repostNotifications() {
        CallService s = instance;
        if (s == null) return;
        s.postNotifications(true);
    }

    // ---- Internals ---------------------------------------------------------

    @SuppressWarnings("deprecation") // Call.getState(): Details.getState() is API 31
    static int stateOf(Call call) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                Call.Details d = call.getDetails();
                if (d != null) return d.getState();
            }
            return call.getState();
        } catch (Throwable t) {
            return CallState.T_DISCONNECTED;
        }
    }

    private static Entry find(Call call) {
        for (Entry e : entries) if (e.call == call) return e;
        return null;
    }

    private static int[] states() {
        int[] s = new int[entries.size()];
        for (int i = 0; i < s.length; i++) s[i] = entries.get(i).state();
        return s;
    }

    private static long[] orders() {
        long[] o = new long[entries.size()];
        for (int i = 0; i < o.length; i++) o[i] = entries.get(i).order;
        return o;
    }

    private static void rememberEnded(Entry e) {
        CallState.End end = CallState.end(e.disconnectCode());
        // DISCONNECTING comes before the cause is known: a later, more telling
        // cause (busy, failed) replaces a plain "ended" for the same call.
        if (lastEnded != null && lastEnded.key == e.key && end == CallState.End.ENDED) return;
        lastEnded = new Ended(e.key, e.title(), end);
    }

    /** Something changed: notifications, wake lock, then the screen. */
    private void changed() {
        try { postNotifications(false); } catch (Throwable t) { Log.e(TAG, "notify", t); }
        try {
            Entry p = primary();
            setProximity(p != null && CallState.proximityWanted(p.screen(), audioRoute()));
        } catch (Throwable t) { Log.e(TAG, "proximity", t); }
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) {
            try { l.onCallsChanged(); } catch (Throwable t) { Log.e(TAG, "listener", t); }
        }
    }

    /**
     * The incoming notification is LOUD (heads-up channel, full-screen intent)
     * unless our call screen is in front: a sticky heads-up would cover the
     * caller's name. When our screen is in front it is QUIET (no heads-up),
     * but keeps Answer / Decline in the notification shade.
     *
     * On an unlocked phone in use, the directly started screen nearly always
     * wins, so the loud post waits SCREEN_WAIT_MS for it and is only made if the
     * screen did not appear. Locked or with the screen off, the full-screen
     * intent is what reliably wakes the phone, so it is posted at once.
     */
    private void postNotifications(boolean force) {
        main.removeCallbacks(recheck);
        int[] s = states();
        long[] o = orders();
        int r = CallState.newestRinging(s, o);
        if (r >= 0) {
            Entry e = entries.get(r);
            if (CallActivity.inFront(this)) {
                CallNotifier.showIncoming(this, e.key, e.title(), false, force);
            } else {
                long waited = SystemClock.elapsedRealtime() - e.addedAt;
                if (waited < SCREEN_WAIT_MS && inUse()) {
                    main.postDelayed(recheck, SCREEN_WAIT_MS - waited);
                } else {
                    CallNotifier.showIncoming(this, e.key, e.title(), true, force);
                }
            }
        } else {
            CallNotifier.cancelIncoming(this);
        }
        int g = CallState.newestOngoing(s, o);
        if (g >= 0) {
            Entry e = entries.get(g);
            CallNotifier.showOngoing(this, e.key, e.title(), e.screen(), e.connectTime(), force);
        } else {
            CallNotifier.cancelOngoing(this);
        }
    }

    /** Switched on and unlocked: someone is looking at the phone right now. */
    private boolean inUse() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            KeyguardManager km = getSystemService(KeyguardManager.class);
            return pm != null && pm.isInteractive() && km != null && !km.isKeyguardLocked();
        } catch (Throwable t) {
            return false; // unsure: post the full-screen notification at once
        }
    }

    /** CallActivity came to the front or left it: switch the notification's loudness. */
    static void screenVisibilityChanged() {
        CallService s = instance;
        if (s != null) s.changed();
    }

    private void setProximity(boolean on) {
        try {
            if (on) {
                if (proximity == null) {
                    PowerManager pm = getSystemService(PowerManager.class);
                    if (pm == null || !pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return;
                    proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SeniorPhone:proximity");
                    proximity.setReferenceCounted(false);
                }
                // Time limit only as a safety net: released when the call ends.
                if (!proximity.isHeld()) proximity.acquire(4 * 60 * 60 * 1000L);
            } else if (proximity != null && proximity.isHeld()) {
                // The screen comes back when the phone leaves the ear, not while
                // it is still against the cheek.
                proximity.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
            }
        } catch (Throwable t) {
            Log.e(TAG, "proximity lock", t);
        }
    }

    /** Formats a number for reading, in the phone's country. */
    static String format(String number) {
        try {
            String iso = null;
            Context c = instance;
            if (c != null) {
                TelephonyManager tm = c.getSystemService(TelephonyManager.class);
                if (tm != null) iso = tm.getNetworkCountryIso();
            }
            if (iso == null || iso.isEmpty()) iso = Locale.getDefault().getCountry();
            String f = PhoneNumberUtils.formatNumber(number, iso.toUpperCase(Locale.ROOT));
            return f != null ? f : number;
        } catch (Throwable t) {
            return number;
        }
    }

    /**
     * Reads the number from the call and, if READ_CONTACTS is granted, the
     * contact's name and photo on a background thread. Without the permission
     * (or any failure) the number is shown instead.
     */
    private void lookUp(Entry e) {
        String number = null;
        try {
            Call.Details d = e.call.getDetails();
            if (d != null && d.getHandlePresentation() == TelecomManager.PRESENTATION_ALLOWED) {
                Uri h = d.getHandle();
                if (h != null) number = h.getSchemeSpecificPart();
            }
        } catch (Throwable t) { Log.e(TAG, "handle", t); }
        if (number != null && number.trim().isEmpty()) number = null;
        if (number == null ? e.lookedUp == null && e.number == null : number.equals(e.lookedUp)) return;
        e.number = number;
        e.lookedUp = number;
        e.contactName = null;
        e.contactId = -1;
        e.photo = null;
        if (number == null) return;
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return;

        final String n = number;
        final ContentResolver cr = getContentResolver();
        final int size = Ui.dp(this, 160);
        try {
            lookups.execute(() -> {
                String name = null;
                long id = -1;
                Bitmap photo = null;
                Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(n));
                String[] cols = { ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.PHOTO_URI };
                try (Cursor c = cr.query(uri, cols, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        name = c.getString(0);
                        id = c.getLong(1);
                        String p = c.getString(2);
                        if (p != null) photo = loadPhoto(cr, Uri.parse(p), size);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "contact lookup", t);
                }
                final String fName = name;
                final long fId = id;
                final Bitmap fPhoto = photo;
                main.post(() -> {
                    // Only if the call still has the number we looked up.
                    if (!entries.contains(e) || !n.equals(e.lookedUp)) return;
                    e.contactName = fName;
                    e.contactId = fId;
                    e.photo = fPhoto;
                    changed();
                });
            });
        } catch (Throwable t) {
            Log.e(TAG, "lookup start", t);
        }
    }

    private static Bitmap loadPhoto(ContentResolver cr, Uri uri, int size) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = cr.openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            try (InputStream in = cr.openInputStream(uri)) {
                return in == null ? null : BitmapFactory.decodeStream(in, null, d);
            }
        } catch (Throwable t) {
            Log.w(TAG, "photo", t);
            return null;
        }
    }
}
