package app.phueber.seniorphone;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The helper's settings: lock the contacts, pick the colours, make this the
 * phone app, open the phone's own settings. Each option has one sentence under
 * it that says what it is for (Bleta's pattern). Everything else (ringtone,
 * volume, text size) is already in the phone's settings and is not duplicated.
 */
public final class SettingsActivity extends Activity {
    private static final String STATE_DECLINED = "declined";

    private Store store;
    /** Filled by showPhoneApp(): its state can change while we are away (Android's own question). */
    private LinearLayout phoneApp;
    /** Android's question came back without "yes": also offer the default-apps page. */
    private boolean declined;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        store = new Store(this);
        declined = savedInstanceState != null && savedInstanceState.getBoolean(STATE_DECLINED);
        LinearLayout content = Ui.screen(this, getString(R.string.settings_title));

        if (LauncherLock.present(this)) {
            // SeniorLauncher is installed: one lock for the whole phone, set and
            // lifted on the home screen. A second lock here would only confuse.
            content.addView(heading(getString(R.string.settings_lock)), Ui.fullWidth(this, 8));
            content.addView(explanation(getString(store.lockedByLauncher()
                    ? R.string.settings_lock_with_launcher_on
                    : R.string.settings_lock_with_launcher_off)), Ui.fullWidth(this, 8));
        } else {
            // Lock: long press only, like the unlock on the contact list, so a senior
            // tapping around cannot lock by accident. A short tap says what to do.
            Button lock = Ui.button(this, getString(R.string.settings_lock), R.color.green);
            TextView hint = Ui.bold(this, getString(R.string.settings_lock_hint), Ui.TEXT_BODY);
            hint.setTextColor(Ui.color(this, R.color.red));
            hint.setVisibility(View.GONE);
            lock.setOnClickListener(v -> {
                hint.setVisibility(View.VISIBLE);
                hint.announceForAccessibility(hint.getText());
            });
            lock.setOnLongClickListener(v -> {
                store.setLocked(true);
                finish(); // back to the contact list, which now shows only the padlock
                return true;
            });
            content.addView(lock, Ui.fullWidth(this, 8));
            content.addView(hint, Ui.fullWidth(this, 8));
            content.addView(explanation(getString(R.string.settings_lock_explain)), Ui.fullWidth(this, 8));
        }

        // Colours: a few fixed, tested themes, no free picker (see Palette).
        // The chosen one is green with a tick; the others are outlined.
        content.addView(heading(getString(R.string.settings_colours)), Ui.fullWidth(this, 36));
        Palette.Theme current = store.theme();
        for (Palette.Theme t : Palette.Theme.values()) {
            String name = getString(themeName(t));
            Button b = t == current
                    ? Ui.button(this, getString(R.string.settings_colour_chosen, name), R.color.green)
                    : Ui.outlined(this, name);
            b.setSelected(t == current);
            b.setOnClickListener(v -> {
                if (t == store.theme()) return;
                store.setTheme(t);
                recreate(); // redraw this screen in the new colours at once
            });
            content.addView(b, Ui.fullWidth(this, 8));
        }
        content.addView(explanation(getString(R.string.settings_colours_explain)), Ui.fullWidth(this, 8));

        // Phone app: the state in words, and the way to change it (see Role).
        content.addView(heading(getString(R.string.settings_phone_app)), Ui.fullWidth(this, 36));
        phoneApp = new LinearLayout(this);
        phoneApp.setOrientation(LinearLayout.VERTICAL);
        content.addView(phoneApp, Ui.fullWidth(this, 0));
        content.addView(explanation(getString(R.string.settings_phone_app_explain)), Ui.fullWidth(this, 8));

        Button phone = Ui.button(this, getString(R.string.settings_phone), R.color.blue);
        phone.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                // No settings screen to open.
            }
        });
        content.addView(phone, Ui.fullWidth(this, 36));
        content.addView(explanation(getString(R.string.settings_phone_explain)), Ui.fullWidth(this, 8));
    }

    @Override
    protected void onResume() {
        super.onResume();
        showPhoneApp(); // also after coming back from Android's question or the default-apps page
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_DECLINED, declined);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != Role.REQUEST_CODE) return;
        // Android 10+ stops showing its question after two "no"s and answers
        // at once; then the default-apps page is the only way left.
        declined = !Role.isDefault(this);
        showPhoneApp();
    }

    private void showPhoneApp() {
        if (phoneApp == null) return;
        phoneApp.removeAllViews();
        TextView state;
        if (Role.isDefault(this)) {
            state = Ui.bold(this, getString(R.string.settings_phone_app_yes), Ui.TEXT_BUTTON);
            state.setTextColor(Ui.color(this, R.color.green));
            phoneApp.addView(state, Ui.fullWidth(this, 8));
            return;
        }
        if (!Role.isAvailable(this)) {
            state = Ui.bold(this, getString(R.string.settings_phone_app_unavailable), Ui.TEXT_BUTTON);
            state.setTextColor(Ui.color(this, R.color.text_muted));
            phoneApp.addView(state, Ui.fullWidth(this, 8));
            return;
        }
        state = Ui.bold(this, getString(R.string.settings_phone_app_no), Ui.TEXT_BUTTON);
        state.setTextColor(Ui.color(this, R.color.red));
        phoneApp.addView(state, Ui.fullWidth(this, 8));

        Button make = Ui.button(this, getString(R.string.settings_phone_app_make), R.color.green);
        make.setOnClickListener(v -> {
            if (!Role.request(this)) {
                declined = true; // this phone has no such question: go to the list
                Role.openDefaultAppsSettings(this);
            }
        });
        phoneApp.addView(make, Ui.fullWidth(this, 8));

        if (declined) {
            Button choose = Ui.outlined(this, getString(R.string.settings_phone_app_choose));
            choose.setOnClickListener(v -> Role.openDefaultAppsSettings(this));
            phoneApp.addView(choose, Ui.fullWidth(this, 8));
        }
    }

    private static int themeName(Palette.Theme t) {
        switch (t) {
            case DARK: return R.string.settings_colour_dark;
            case HIGH_CONTRAST: return R.string.settings_colour_high_contrast;
            case COLOURFUL: return R.string.settings_colour_colourful;
            default: return R.string.settings_colour_light;
        }
    }

    private TextView heading(CharSequence s) {
        TextView t = Ui.bold(this, s, Ui.TEXT_BUTTON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) t.setAccessibilityHeading(true);
        return t;
    }

    private TextView explanation(CharSequence s) {
        TextView t = Ui.text(this, s, Ui.TEXT_BODY);
        t.setTextColor(Ui.color(this, R.color.text_muted));
        t.setTypeface(Typeface.defaultFromStyle(Typeface.ITALIC));
        return t;
    }
}
