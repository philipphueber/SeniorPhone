package app.phueber.seniorphone;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The PIN prompts of the lock, in the style of {@link Ui#confirm}: choosing a
 * PIN when locking, entering it when unlocking. Works like SeniorAlert's lock.
 *
 * The unlock prompt never lingers: after {@link #IDLE_MS} without typing it
 * closes and the locked screen is back, so a prompt opened by accident does not
 * stay up asking the senior for a code. Each digit restarts the countdown.
 */
final class PinDialog {
    static final long IDLE_MS = 10_000L;

    private PinDialog() {}

    /** Asks for a new PIN twice; calls onChosen with it when both match and it is valid. */
    static Dialog choose(Activity a, Consumer<String> onChosen) {
        Dialog d = new Dialog(a);
        LinearLayout box = box(a, a.getString(R.string.pin_lock_title),
                a.getString(R.string.pin_lock_message));
        EditText pin = field(a, R.string.pin_hint);
        EditText repeat = field(a, R.string.pin_repeat_hint);
        TextView error = error(a);
        box.addView(pin, Ui.fullWidth(a, 16));
        box.addView(repeat, Ui.fullWidth(a, 8));
        box.addView(error, Ui.fullWidth(a, 8));
        Button ok = Ui.button(a, a.getString(R.string.pin_lock), R.color.green);
        ok.setOnClickListener(v -> {
            String p1 = pin.getText().toString();
            if (!Pin.isValid(p1)) {
                say(error, R.string.pin_invalid);
            } else if (!p1.equals(repeat.getText().toString())) {
                repeat.setText("");
                repeat.requestFocus();
                say(error, R.string.pin_mismatch);
            } else {
                d.dismiss();
                onChosen.accept(p1);
            }
        });
        finish(a, d, box, ok);
        return d;
    }

    /**
     * Asks for the PIN; check says whether it is right. Right: the prompt closes
     * and onUnlocked runs. Wrong: the field clears and says so.
     */
    static Dialog unlock(Activity a, Predicate<String> check, Runnable onUnlocked) {
        Dialog d = new Dialog(a);
        LinearLayout box = box(a, a.getString(R.string.pin_unlock_title),
                a.getString(R.string.pin_unlock_message));
        EditText pin = field(a, R.string.pin_hint);
        TextView error = error(a);
        box.addView(pin, Ui.fullWidth(a, 16));
        box.addView(error, Ui.fullWidth(a, 8));
        Button ok = Ui.button(a, a.getString(R.string.pin_unlock), R.color.green);
        ok.setOnClickListener(v -> {
            if (check.test(pin.getText().toString())) {
                d.dismiss();
                onUnlocked.run();
            } else {
                pin.setText("");
                say(error, R.string.pin_wrong);
            }
        });

        Handler h = new Handler(Looper.getMainLooper());
        Runnable autoClose = d::dismiss;
        pin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void afterTextChanged(Editable s) {
                h.removeCallbacks(autoClose);
                h.postDelayed(autoClose, IDLE_MS);
            }
        });
        d.setOnDismissListener(x -> h.removeCallbacks(autoClose));
        finish(a, d, box, ok);
        h.postDelayed(autoClose, IDLE_MS);
        return d;
    }

    private static LinearLayout box(Activity a, String title, String message) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 24);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(a, R.color.card));
        bg.setCornerRadius(Ui.dp(a, 24));
        box.setBackground(bg);
        TextView t = Ui.bold(a, title, 24);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        TextView m = Ui.text(a, message, 18);
        m.setGravity(Gravity.CENTER);
        box.addView(m, Ui.fullWidth(a, 8));
        return box;
    }

    private static EditText field(Activity a, int hintRes) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint(hintRes);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        e.setGravity(Gravity.CENTER);
        e.setTextColor(Ui.color(a, R.color.text_black));
        e.setHintTextColor(Ui.color(a, R.color.text_muted));
        e.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(Pin.MAX_LENGTH)});
        return e;
    }

    private static TextView error(Activity a) {
        TextView e = Ui.bold(a, "", 18);
        e.setTextColor(Ui.color(a, R.color.red));
        e.setGravity(Gravity.CENTER);
        return e;
    }

    private static void say(TextView error, int res) {
        error.setText(res);
        error.announceForAccessibility(error.getText());
    }

    /** OK and Cancel at the bottom, scrolling at a large font, keyboard up. */
    private static void finish(Activity a, Dialog d, LinearLayout box, Button ok) {
        box.addView(ok, Ui.fullWidth(a, 16));
        Button no = Ui.button(a, a.getString(R.string.cancel), R.color.blue);
        no.setOnClickListener(v -> d.dismiss());
        box.addView(no, Ui.fullWidth(a, 12));
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(a.getResources().getDisplayMetrics().widthPixels - Ui.dp(a, 32),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        d.show();
    }
}
