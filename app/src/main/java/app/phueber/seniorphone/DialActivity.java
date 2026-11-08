package app.phueber.seniorphone;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The number pad: the typed number big on top with a square Delete (⌫) beside
 * it, twelve keys with only their big digit near the bottom (where the thumb is),
 * a big green Call, and Back. Also what other apps
 * open with "dial this number" (ACTION_DIAL / VIEW tel:), pre-filled.
 *
 * No gestures: every key is a plain tap. Two helper-ish long presses only,
 * both also reachable another way: hold 0 for "+" (shown on the key), hold
 * Delete to clear the whole number (tapping it repeatedly does the same).
 * Call goes straight out without a "Call …?" question: the number was just
 * typed on purpose, and Call stays greyed out until it has a digit.
 */
public final class DialActivity extends Activity {
    private static final String STATE_NUMBER = "number";
    private static final int KEY_MIN_HEIGHT = 80;
    private static final int DIGIT_SP = 52;
    /** Pad rows keep this height instead of stretching over the whole screen. */
    private static final int KEY_HEIGHT = 76;
    private static final int LETTERS_SP = 16;
    private static final int TONE_MS = 150;
    private static final String KEYS = "123456789*0#";

    private Palette.Theme theme;
    private String number = "";
    private TextView display;
    private Button delete;
    private Button call;
    private ToneGenerator tones;
    private android.app.Dialog roleDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        theme = Ui.applyTheme(this);
        number = savedInstanceState != null
                ? DialNumber.clean(savedInstanceState.getString(STATE_NUMBER, ""))
                : fromIntent(getIntent());
        setContentView(build());
        update();
        // The Phone icon is a front door of its own, so the role question belongs here too.
        // Not over a number another app handed us (ACTION_DIAL / tel:): that would interrupt it.
        Intent in = getIntent();
        if (savedInstanceState == null && in != null && in.getData() == null
                && (in.getAction() == null || Intent.ACTION_MAIN.equals(in.getAction()))) {
            roleDialog = Role.askIfNeeded(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Role.dismiss(this, roleDialog);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String n = fromIntent(intent);
        if (!n.isEmpty()) {
            number = n;
            update();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_NUMBER, number);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (new Store(this).theme() != theme) {
            recreate(); // the helper changed the colours meanwhile
            return;
        }
        // Key tones like every phone, unless the phone's own setting turned them off.
        if (Settings.System.getInt(getContentResolver(), Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0) {
            try {
                tones = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
            } catch (RuntimeException e) {
                tones = null; // no tone is fine; a crash is not
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tones != null) {
            tones.release();
            tones = null;
        }
    }

    /**
     * Calls.place asks for CALL_PHONE when it is missing. When the answer is
     * yes, the call the senior asked for goes out without a second tap.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        for (int i = 0; i < permissions.length && i < results.length; i++) {
            if (Manifest.permission.CALL_PHONE.equals(permissions[i])
                    && results[i] == PackageManager.PERMISSION_GRANTED
                    && DialNumber.canCall(number)) {
                Calls.place(this, number);
                return;
            }
        }
    }

    private Boolean displayEmpty;

    private void sizeDisplay(boolean empty) {
        if (display == null || Boolean.valueOf(empty).equals(displayEmpty)) return;
        displayEmpty = empty;
        if (empty) {
            display.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE);
            display.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            display.setMaxLines(2); // the hint may wrap rather than be cut
            display.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            // The whole number always shows: never cut, it shrinks and takes a
            // second line instead. Sized in dp, not sp, because auto-size already
            // makes it as big as the box allows; in sp a large font setting would
            // raise the smallest size until a long number no longer fits.
            display.setEllipsize(null);
            display.setMaxLines(2);
            display.setAutoSizeTextTypeUniformWithConfiguration(14, 56, 1, TypedValue.COMPLEX_UNIT_DIP);
        }
    }

    /** The number in a "tel:" link from another app, cleaned; "" if there is none. */
    private static String fromIntent(Intent i) {
        Uri data = i == null ? null : i.getData();
        if (data == null || !"tel".equalsIgnoreCase(data.getScheme())) return "";
        return DialNumber.clean(data.getSchemeSpecificPart());
    }

    private View build() {
        int p = Ui.dp(this, 16);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);

        // Only Delete sits on top, at the end. The number goes in the middle, in
        // the spare room between Delete and the pad, where it is easy to read.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        top.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 8));

        display = Ui.bold(this, "", 48);
        display.setGravity(Gravity.CENTER);
        display.setHint(R.string.dial_hint);
        // Empty, the field shows the hint "Type a number". Auto-size only shrinks
        // typed text, never a hint, so at a large font size the hint would be cut
        // to "...mber". While empty: a smaller fixed size on up to two lines; once a
        // digit is typed: auto-size again, on up to two lines, never cut.
        display.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                sizeDisplay(s.length() == 0);
            }
        });
        sizeDisplay(true);
        display.setHintTextColor(Ui.color(this, R.color.text_muted));
        display.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        display.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        // A square with only the backspace sign, so the number gets the width.
        // TalkBack still hears "Delete".
        delete = Ui.button(this, "", R.color.blue);
        delete.setMinHeight(Ui.dp(this, KEY_MIN_HEIGHT));
        delete.setMinimumHeight(Ui.dp(this, KEY_MIN_HEIGHT));
        delete.setMinWidth(Ui.dp(this, KEY_MIN_HEIGHT));
        delete.setMinimumWidth(Ui.dp(this, KEY_MIN_HEIGHT));
        delete.setPadding(0, 0, 0, 0);
        delete.setContentDescription(getString(R.string.dial_delete));
        icon(delete, R.drawable.ic_dial_backspace);
        // No text: centre the icon by padding it in from the start.
        Drawable bs = delete.getCompoundDrawablesRelative()[0];
        int iconW = bs != null ? bs.getBounds().width() : 0;
        delete.setCompoundDrawablePadding(0);
        delete.setPaddingRelative((Ui.dp(this, KEY_MIN_HEIGHT) - iconW) / 2, 0, 0, 0);
        delete.setOnClickListener(v -> {
            number = DialNumber.deleteLast(number);
            update();
        });
        delete.setOnLongClickListener(v -> {
            number = "";
            update();
            return true;
        });
        longPressLabel(delete, getString(R.string.dial_delete_all));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                Ui.dp(this, KEY_MIN_HEIGHT), Ui.dp(this, KEY_MIN_HEIGHT));
        top.addView(delete, dlp);
        page.addView(top);

        // The pad and Call. Each row is at least KEY_MIN_HEIGHT and they share
        // any spare height; on a very small screen the pad scrolls instead of
        // shrinking the keys.
        // The pad sits at the bottom, just above Back where the thumb is; spare
        // height goes to the number above it. A ScrollView still, so a very
        // small screen or a huge font scrolls instead of clipping keys.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout pad = new LinearLayout(this);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.BOTTOM);
        pad.setPadding(p - Ui.dp(this, 4), 0, p - Ui.dp(this, 4), Ui.dp(this, 8));
        // The number fills the spare height above the keys, centred in it.
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        nlp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 8));
        display.setMinHeight(Ui.dp(this, KEY_MIN_HEIGHT));
        pad.addView(display, nlp);
        for (int row = 0; row < 4; row++) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            // A minimum, not a fixed height: at a large font scale the keys grow
            // instead of clipping their digit and letters.
            r.setMinimumHeight(Ui.dp(this, KEY_HEIGHT + 8));
            for (int col = 0; col < 3; col++) {
                LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                klp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
                r.addView(key(KEYS.charAt(row * 3 + col)), klp);
            }
            pad.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        call = Ui.button(this, getString(R.string.call), R.color.green);
        call.setMinHeight(Ui.dp(this, KEY_MIN_HEIGHT));
        call.setMinimumHeight(Ui.dp(this, KEY_MIN_HEIGHT));
        call.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        icon(call, R.drawable.ic_dial_call);
        call.setOnClickListener(v -> {
            if (DialNumber.canCall(number)) Calls.place(this, number);
        });
        LinearLayout.LayoutParams clp = Ui.fullWidth(this, 8);
        clp.setMarginStart(Ui.dp(this, 4));
        clp.setMarginEnd(Ui.dp(this, 4));
        pad.addView(call, clp);

        scroll.addView(pad);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(p, Ui.dp(this, 4), p, Ui.dp(this, 12));
        page.addView(Ui.backButton(this), blp);
        return page;
    }

    /**
     * One key: only the digit, big and centred. No letters: seniors dial numbers,
     * not words, and the letters were clutter. The 0 alone shows a small "+" in
     * its top corner (hold 0 for +; TalkBack says so), out of the digit's way.
     */
    private View key(char c) {
        android.widget.FrameLayout k = new android.widget.FrameLayout(this);
        k.setMinimumHeight(Ui.dp(this, KEY_HEIGHT));
        k.setBackground(Ui.ripple(this, R.color.press_dark, Ui.color(this, R.color.card),
                Ui.color(this, R.color.card_border), 20));
        k.setClickable(true);
        k.setFocusable(true);
        k.setSoundEffectsEnabled(false); // the key tone is the sound

        TextView digit = Ui.bold(this, String.valueOf(c), DIGIT_SP);
        digit.setGravity(Gravity.CENTER);
        digit.setIncludeFontPadding(false);
        digit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        k.addView(digit, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        if (c == '0') {
            TextView hint = Ui.bold(this, "+", LETTERS_SP + 6);
            hint.setTextColor(Ui.color(this, R.color.text_muted));
            hint.setMaxLines(1);
            hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            android.widget.FrameLayout.LayoutParams hlp = new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            hlp.topMargin = Ui.dp(this, 4);
            hlp.setMarginEnd(Ui.dp(this, 12));
            k.addView(hint, hlp);
        }

        if (c == '*') k.setContentDescription(getString(R.string.dial_star));
        else if (c == '#') k.setContentDescription(getString(R.string.dial_hash));
        else if (c == '0') k.setContentDescription(getString(R.string.dial_zero_description));
        else k.setContentDescription(String.valueOf(c));

        k.setOnClickListener(v -> press(v, c));
        if (c == '0') {
            // Hold 0 for "+": at the start the international prefix, later just a 0.
            k.setOnLongClickListener(v -> {
                press(v, number.isEmpty() ? '+' : '0');
                return true;
            });
            longPressLabel(k, getString(R.string.dial_plus));
        }
        return k;
    }

    private void press(View v, char c) {
        String next = DialNumber.append(number, c);
        if (next.equals(number)) return; // full, or a + that is not allowed here
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        tone(c);
        number = next;
        update();
    }

    private void tone(char c) {
        if (tones == null) return;
        int t;
        if (c >= '0' && c <= '9') t = ToneGenerator.TONE_DTMF_0 + (c - '0');
        else if (c == '*') t = ToneGenerator.TONE_DTMF_S;
        else if (c == '#') t = ToneGenerator.TONE_DTMF_P;
        else t = ToneGenerator.TONE_DTMF_0; // + is typed on the 0 key
        try {
            tones.startTone(t, TONE_MS);
        } catch (RuntimeException ignored) {
            // A missing tone is not worth a crash.
        }
    }

    /** Shows the number; Call and Delete are greyed out while there is nothing to use. */
    private void update() {
        display.setText(number);
        display.setContentDescription(number.isEmpty() ? getString(R.string.dial_hint)
                : DialNumber.spoken(number, getString(R.string.dial_plus),
                        getString(R.string.dial_star), getString(R.string.dial_hash)));
        Ui.setEnabled(call, DialNumber.canCall(number), R.color.green);
        Ui.setEnabled(delete, !number.isEmpty(), R.color.blue);
        call.setCompoundDrawableTintList(ColorStateList.valueOf(call.getCurrentTextColor()));
        delete.setCompoundDrawableTintList(ColorStateList.valueOf(delete.getCurrentTextColor()));
    }

    /** A leading icon at the size of the button's text, tinted like the text (in update). */
    private static void icon(Button b, int res) {
        Drawable d = b.getContext().getDrawable(res);
        if (d == null) return;
        d = d.mutate();
        int size = Math.round(b.getTextSize() * 1.2f);
        d.setBounds(0, 0, size, size);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(Ui.dp(b.getContext(), 10));
    }

    /** TalkBack says what holding the view does, instead of just "long press". */
    private static void longPressLabel(View v, CharSequence label) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK, label));
            }
        });
    }
}
