package app.phueber.seniorphone;

/**
 * The pure rules of the call screen: which Telecom state shows which screen,
 * which call gets the screen when there are several, what an ended call says,
 * and how a duration is written. No android.* imports, so every rule is
 * checked by a JVM test; callers pass the platform's int constants.
 *
 * The T_ and R_ and D_ values are copies of android.telecom.Call.STATE_*,
 * CallAudioState.ROUTE_* and DisconnectCause.* (public API constants, which
 * never change).
 */
final class CallState {
    // android.telecom.Call.STATE_*
    static final int T_NEW = 0;
    static final int T_DIALING = 1;
    static final int T_RINGING = 2;
    static final int T_HOLDING = 3;
    static final int T_ACTIVE = 4;
    static final int T_DISCONNECTED = 7;
    static final int T_SELECT_PHONE_ACCOUNT = 8;
    static final int T_CONNECTING = 9;
    static final int T_DISCONNECTING = 10;
    static final int T_PULLING_CALL = 11;
    static final int T_AUDIO_PROCESSING = 12;
    static final int T_SIMULATED_RINGING = 13;

    // android.telecom.CallAudioState.ROUTE_*
    static final int R_EARPIECE = 1;
    static final int R_BLUETOOTH = 2;
    static final int R_WIRED_HEADSET = 4;
    static final int R_SPEAKER = 8;

    // android.telecom.DisconnectCause codes
    static final int D_UNKNOWN = 0;
    static final int D_ERROR = 1;
    static final int D_LOCAL = 2;
    static final int D_REMOTE = 3;
    static final int D_CANCELED = 4;
    static final int D_MISSED = 5;
    static final int D_REJECTED = 6;
    static final int D_BUSY = 7;
    static final int D_RESTRICTED = 8;
    static final int D_OTHER = 9;
    static final int D_ANSWERED_ELSEWHERE = 11;

    /** What the call screen shows. */
    enum Screen {
        /** Ringing: Answer / Decline. */
        INCOMING,
        /** We are calling out and the other side has not answered yet. */
        OUTGOING,
        /** A call is being checked by the network or a screening app: only Hang up. */
        WAITING,
        /** Dual SIM without a default: the senior picks the SIM card. */
        CHOOSE_SIM,
        /** Talking. */
        ACTIVE,
        /** Parked by call waiting: Continue / Hang up. */
        HOLDING,
        /** Over (or ending): "Call ended", then the screen closes. */
        ENDED
    }

    /** What the "ended" screen says. */
    enum End { ENDED, DECLINED, MISSED, BUSY, FAILED }

    private CallState() {}

    static Screen screen(int telecomState) {
        switch (telecomState) {
            case T_RINGING:
            case T_SIMULATED_RINGING:
                return Screen.INCOMING;
            case T_NEW:
            case T_DIALING:
            case T_CONNECTING:
            case T_PULLING_CALL:
                return Screen.OUTGOING;
            case T_AUDIO_PROCESSING:
                return Screen.WAITING;
            case T_SELECT_PHONE_ACCOUNT:
                return Screen.CHOOSE_SIM;
            case T_ACTIVE:
                return Screen.ACTIVE;
            case T_HOLDING:
                return Screen.HOLDING;
            default:
                // DISCONNECTED, DISCONNECTING and anything a future Android adds
                // that we do not know: never offer buttons for a call we do not
                // understand; the screen closes, other calls take over.
                return Screen.ENDED;
        }
    }

    static boolean isRinging(int telecomState) {
        return screen(telecomState) == Screen.INCOMING;
    }

    /** Still a call the senior can act on (not ended or ending). */
    static boolean isLive(int telecomState) {
        return screen(telecomState) != Screen.ENDED;
    }

    /**
     * Which call gets the screen. states[i] is call i's Telecom state, order[i]
     * when it was added (bigger = newer). Rules, in order:
     *  1. the newest ringing call (call waiting: the new caller must be
     *     answerable at once; the ongoing call is put on hold by Answer);
     *  2. the newest call that is being set up (outgoing, SIM choice, waiting);
     *  3. the newest active call;
     *  4. the newest held call (so a parked call is never left without a screen).
     * Ended/ending calls never get the screen. -1 if no call is live.
     */
    static int pickPrimary(int[] states, long[] order) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < states.length; i++) {
            int r = rank(screen(states[i]));
            if (r < 0) continue;
            if (r < bestRank || (r == bestRank && order[i] > order[best])) {
                best = i;
                bestRank = r;
            }
        }
        return best;
    }

    /** The newest ringing call, or -1. */
    static int newestRinging(int[] states, long[] order) {
        int best = -1;
        for (int i = 0; i < states.length; i++) {
            if (isRinging(states[i]) && (best < 0 || order[i] > order[best])) best = i;
        }
        return best;
    }

    /**
     * The call the ongoing notification is about: the newest live call that is
     * not ringing, preferring an active one over a held one. -1 if none.
     */
    static int newestOngoing(int[] states, long[] order) {
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < states.length; i++) {
            Screen s = screen(states[i]);
            if (s == Screen.INCOMING) continue;
            int r = rank(s);
            if (r < 0) continue;
            if (r < bestRank || (r == bestRank && order[i] > order[best])) {
                best = i;
                bestRank = r;
            }
        }
        return best;
    }

    private static int rank(Screen s) {
        switch (s) {
            case INCOMING: return 0;
            case OUTGOING:
            case WAITING:
            case CHOOSE_SIM: return 1;
            case ACTIVE: return 2;
            case HOLDING: return 3;
            default: return -1;
        }
    }

    /** The message for a DisconnectCause code. */
    static End end(int disconnectCode) {
        switch (disconnectCode) {
            case D_REJECTED: return End.DECLINED;
            case D_MISSED: return End.MISSED;
            case D_BUSY: return End.BUSY;
            case D_ERROR:
            case D_RESTRICTED: return End.FAILED;
            default: return End.ENDED;
        }
    }

    /**
     * How long the "ended" screen stays. Plain endings close quickly; a busy
     * line or a failed call is news the senior must have time to read.
     */
    static long endedShowMillis(End end) {
        return end == End.ENDED || end == End.DECLINED ? 2500 : 4500;
    }

    /**
     * Call length for the screen and notification: "0:07", "12:34",
     * "1:02:03". Negative (clock changed) counts as 0.
     */
    static String duration(long millis) {
        long s = Math.max(0, millis) / 1000;
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        String ss = sec < 10 ? "0" + sec : String.valueOf(sec);
        if (h > 0) return h + ":" + (m < 10 ? "0" + m : String.valueOf(m)) + ":" + ss;
        return m + ":" + ss;
    }

    /**
     * The name to show big: the address-book name, else the name the network
     * sent, else the number. null means none is known ("Unknown number").
     */
    static String title(String contactName, String networkName, String number) {
        if (notBlank(contactName)) return contactName.trim();
        if (notBlank(networkName)) return networkName.trim();
        if (notBlank(number)) return number.trim();
        return null;
    }

    /** The letter on the round avatar, or null for "show the person icon". */
    static String initial(String contactName) {
        if (!notBlank(contactName)) return null;
        String t = contactName.trim();
        int cp = t.codePointAt(0);
        if (!Character.isLetter(cp)) return null;
        return new String(Character.toChars(cp)).toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Whether the proximity sensor should switch the screen off: only while a
     * call is connected or being set up through the earpiece, i.e. the phone is
     * at the ear. Never while ringing (the screen must show Answer), never on
     * speaker or headsets (the phone is in front of the senior).
     */
    static boolean proximityWanted(Screen screen, int audioRoute) {
        return (screen == Screen.ACTIVE || screen == Screen.OUTGOING || screen == Screen.WAITING)
                && audioRoute == R_EARPIECE;
    }

    /** The route for "speaker off": back to Bluetooth if it is there, else earpiece/headset. */
    static int routeForSpeakerOff(int supportedRouteMask) {
        return (supportedRouteMask & R_BLUETOOTH) != 0 ? R_BLUETOOTH : (R_EARPIECE | R_WIRED_HEADSET);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
