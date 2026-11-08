package app.phueber.seniorphone;

import static app.phueber.seniorphone.CallState.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CallStateTest {

    @Test
    public void everyTelecomStateHasAScreen() {
        assertEquals(Screen.INCOMING, screen(T_RINGING));
        assertEquals(Screen.INCOMING, screen(T_SIMULATED_RINGING));
        assertEquals(Screen.OUTGOING, screen(T_NEW));
        assertEquals(Screen.OUTGOING, screen(T_DIALING));
        assertEquals(Screen.OUTGOING, screen(T_CONNECTING));
        assertEquals(Screen.OUTGOING, screen(T_PULLING_CALL));
        assertEquals(Screen.WAITING, screen(T_AUDIO_PROCESSING));
        assertEquals(Screen.CHOOSE_SIM, screen(T_SELECT_PHONE_ACCOUNT));
        assertEquals(Screen.ACTIVE, screen(T_ACTIVE));
        assertEquals(Screen.HOLDING, screen(T_HOLDING));
        assertEquals(Screen.ENDED, screen(T_DISCONNECTED));
        assertEquals(Screen.ENDED, screen(T_DISCONNECTING));
    }

    @Test
    public void unknownFutureStateNeverOffersButtons() {
        assertEquals(Screen.ENDED, screen(99));
        assertEquals(Screen.ENDED, screen(-1));
        assertFalse(isLive(99));
    }

    @Test
    public void liveAndRinging() {
        assertTrue(isRinging(T_RINGING));
        assertFalse(isRinging(T_ACTIVE));
        assertTrue(isLive(T_HOLDING));
        assertFalse(isLive(T_DISCONNECTING));
        assertFalse(isLive(T_DISCONNECTED));
    }

    @Test
    public void noCallsNoPrimary() {
        assertEquals(-1, pickPrimary(new int[0], new long[0]));
        assertEquals(-1, pickPrimary(new int[] {T_DISCONNECTED, T_DISCONNECTING}, new long[] {1, 2}));
    }

    @Test
    public void singleCall() {
        assertEquals(0, pickPrimary(new int[] {T_ACTIVE}, new long[] {5}));
        assertEquals(0, pickPrimary(new int[] {T_RINGING}, new long[] {5}));
    }

    @Test
    public void callWaitingRingingCallWinsOverActive() {
        int[] s = {T_ACTIVE, T_RINGING};
        long[] o = {1, 2};
        assertEquals(1, pickPrimary(s, o));
        // ... whatever the list order is.
        assertEquals(0, pickPrimary(new int[] {T_RINGING, T_ACTIVE}, new long[] {2, 1}));
    }

    @Test
    public void newestRingingCallWins() {
        int[] s = {T_RINGING, T_RINGING, T_ACTIVE};
        long[] o = {1, 3, 2};
        assertEquals(1, pickPrimary(s, o));
        assertEquals(1, newestRinging(s, o));
    }

    @Test
    public void afterAnsweringActiveWinsOverHeld() {
        assertEquals(1, pickPrimary(new int[] {T_HOLDING, T_ACTIVE}, new long[] {1, 2}));
        // Even when the held call is the newer one.
        assertEquals(1, pickPrimary(new int[] {T_HOLDING, T_ACTIVE}, new long[] {2, 1}));
    }

    @Test
    public void heldCallStaysReachableWhenAloneOrBesideEnded() {
        assertEquals(0, pickPrimary(new int[] {T_HOLDING, T_DISCONNECTED}, new long[] {1, 2}));
    }

    @Test
    public void endedCallNeverWins() {
        assertEquals(1, pickPrimary(new int[] {T_DISCONNECTING, T_HOLDING}, new long[] {9, 1}));
    }

    @Test
    public void outgoingWinsOverActive() {
        assertEquals(1, pickPrimary(new int[] {T_ACTIVE, T_DIALING}, new long[] {1, 2}));
        assertEquals(1, pickPrimary(new int[] {T_HOLDING, T_SELECT_PHONE_ACCOUNT}, new long[] {1, 2}));
    }

    @Test
    public void ongoingNotificationSkipsRingingAndEnded() {
        int[] s = {T_HOLDING, T_RINGING, T_ACTIVE, T_DISCONNECTED};
        long[] o = {1, 4, 2, 3};
        assertEquals(2, newestOngoing(s, o));
        assertEquals(-1, newestOngoing(new int[] {T_RINGING}, new long[] {1}));
        assertEquals(0, newestOngoing(new int[] {T_HOLDING, T_RINGING}, new long[] {1, 2}));
        assertEquals(-1, newestRinging(new int[] {T_ACTIVE}, new long[] {1}));
    }

    @Test
    public void endMessages() {
        assertEquals(End.BUSY, end(D_BUSY));
        assertEquals(End.DECLINED, end(D_REJECTED));
        assertEquals(End.MISSED, end(D_MISSED));
        assertEquals(End.FAILED, end(D_ERROR));
        assertEquals(End.FAILED, end(D_RESTRICTED));
        assertEquals(End.ENDED, end(D_LOCAL));
        assertEquals(End.ENDED, end(D_REMOTE));
        assertEquals(End.ENDED, end(D_UNKNOWN));
        assertEquals(End.ENDED, end(D_ANSWERED_ELSEWHERE));
        assertEquals(End.ENDED, end(1234));
    }

    @Test
    public void badNewsStaysLongerOnScreen() {
        assertTrue(endedShowMillis(End.BUSY) > endedShowMillis(End.ENDED));
        assertTrue(endedShowMillis(End.FAILED) > endedShowMillis(End.ENDED));
        assertTrue(endedShowMillis(End.ENDED) >= 2000);
    }

    @Test
    public void durations() {
        assertEquals("0:00", duration(0));
        assertEquals("0:00", duration(-5000));
        assertEquals("0:07", duration(7999));
        assertEquals("1:00", duration(60_000));
        assertEquals("12:34", duration((12 * 60 + 34) * 1000L));
        assertEquals("59:59", duration(3_599_000));
        assertEquals("1:00:00", duration(3_600_000));
        assertEquals("1:02:03", duration((3600 + 2 * 60 + 3) * 1000L));
        assertEquals("25:00:00", duration(25 * 3_600_000L));
    }

    @Test
    public void titlePrefersContactThenNetworkThenNumber() {
        assertEquals("Maria", title("Maria", "M SCHMIDT", "+49 30 1234"));
        assertEquals("M SCHMIDT", title(null, "M SCHMIDT", "+49 30 1234"));
        assertEquals("M SCHMIDT", title("  ", "M SCHMIDT", "+49 30 1234"));
        assertEquals("+49 30 1234", title(null, "", "+49 30 1234"));
        assertNull(title(null, null, null));
        assertNull(title("", " ", ""));
        assertEquals("Maria", title("  Maria ", null, null));
    }

    @Test
    public void initials() {
        assertEquals("M", initial("maria"));
        assertEquals("Ä", initial("ärztin"));
        assertNull(initial("+49 30"));
        assertNull(initial(null));
        assertNull(initial(" "));
        // A letter outside the basic plane stays whole.
        assertEquals(new String(Character.toChars(0x10400)), initial(new String(Character.toChars(0x10428)) + "x"));
    }

    @Test
    public void proximityOnlyAtTheEar() {
        assertTrue(proximityWanted(Screen.ACTIVE, R_EARPIECE));
        assertTrue(proximityWanted(Screen.OUTGOING, R_EARPIECE));
        assertFalse(proximityWanted(Screen.INCOMING, R_EARPIECE));
        assertFalse(proximityWanted(Screen.HOLDING, R_EARPIECE));
        assertFalse(proximityWanted(Screen.CHOOSE_SIM, R_EARPIECE));
        assertFalse(proximityWanted(Screen.ENDED, R_EARPIECE));
        assertFalse(proximityWanted(Screen.ACTIVE, R_SPEAKER));
        assertFalse(proximityWanted(Screen.ACTIVE, R_BLUETOOTH));
        assertFalse(proximityWanted(Screen.ACTIVE, R_WIRED_HEADSET));
        assertFalse(proximityWanted(Screen.ACTIVE, 0));
    }

    @Test
    public void speakerOffGoesBackToBluetoothIfThere() {
        assertEquals(R_BLUETOOTH, routeForSpeakerOff(R_EARPIECE | R_BLUETOOTH | R_SPEAKER));
        assertEquals(R_EARPIECE | R_WIRED_HEADSET, routeForSpeakerOff(R_EARPIECE | R_SPEAKER));
        // 5 is CallAudioState.ROUTE_WIRED_OR_EARPIECE.
        assertEquals(5, routeForSpeakerOff(0));
    }
}
