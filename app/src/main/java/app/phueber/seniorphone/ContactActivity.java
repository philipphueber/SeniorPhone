package app.phueber.seniorphone;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.telephony.PhoneNumberUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One contact (Bleta's contact page): a top bar Back · Edit · Delete, the big
 * round photo or initial, the name, and a big green Call button above the
 * number in large text. A contact with several numbers gets one Call button
 * per number, named by its type ("Call · Mobile"), so the senior never has to
 * pick a number from a small menu.
 *
 * Call always asks first ("Call Maria Schmidt?"): a stray tap on the biggest
 * button of the screen must not start a call.
 *
 * Locked, the bar has only Back: Edit and Delete are for the helper.
 */
public final class ContactActivity extends Activity {
    /** The aggregate contact id (ContactsContract.Contacts._ID), a long. */
    public static final String EXTRA_ID = "contact_id";
    private static final int AVATAR_DP = 160;

    private Store store;
    private Palette.Theme shownTheme;
    private long id;
    private LinearLayout topBar;
    private LinearLayout content;
    private ContactInfo contact;
    private Dialog dialog;
    /** Counts loads, so a slow older load cannot replace a newer result. */
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownTheme = Ui.applyTheme(this);
        store = new Store(this);
        id = getIntent().getLongExtra(EXTRA_ID, -1);
        if (id < 0) {
            finish();
            return;
        }
        int p = Ui.dp(this, 16);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(p, Ui.dp(this, 8), p, 0);

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(topBar, Ui.fullWidth(this, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
        scroll.addView(content);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        // Back pinned at the bottom, as on every Senior screen.
        LinearLayout.LayoutParams blp = Ui.fullWidth(this, 8);
        blp.bottomMargin = Ui.dp(this, 12);
        page.addView(Ui.backButton(this), blp);
        // fitsSystemWindows on a frame of its own: on the padded page itself it
        // would replace the side padding with the (zero) side insets.
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setFitsSystemWindows(true);
        frame.addView(page);
        setContentView(frame);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store.theme() != shownTheme) {
            recreate();
            return;
        }
        renderTopBar();
        if (!Contacts.canRead(this)) {
            finish(); // permission taken away in the phone settings
            return;
        }
        load(); // also after Edit, which comes back here
    }

    @Override
    protected void onStop() {
        super.onStop();
        dismissDialog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        generation++;
    }

    private void load() {
        int gen = ++generation;
        ContentResolver cr = getContentResolver();
        Contacts.BACKGROUND.execute(() -> {
            ContactInfo c = Contacts.load(cr, id);
            Contacts.MAIN.post(() -> {
                if (gen != generation || isDestroyed()) return;
                if (c == null) {
                    finish(); // deleted meanwhile, here or in another app
                    return;
                }
                contact = c;
                show(c);
            });
        });
    }

    // ---------------------------------------------------------------- top bar

    private void renderTopBar() {
        topBar.removeAllViews();
        // Back is at the bottom; the top holds the helper's Edit and Delete,
        // and disappears when locked.
        boolean locked = store.locked();
        topBar.setVisibility(locked ? View.GONE : View.VISIBLE);
        if (locked) return;
        addTop(R.drawable.ic_contacts_edit, getString(R.string.contacts_edit), R.color.text_black,
                v -> {
                    if (store.locked()) return;
                    startActivity(new Intent(this, EditContactActivity.class)
                            .putExtra(EditContactActivity.EXTRA_ID, id));
                });
        addTop(R.drawable.ic_contacts_delete, getString(R.string.contacts_delete), R.color.red,
                v -> askDelete());
    }

    /** Icon above a word, three equal tiles in a row (Bleta). Delete's icon and word are red. */
    private void addTop(int iconRes, String label, int colorRes, View.OnClickListener click) {
        int fg = Ui.color(this, colorRes);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(Ui.dp(this, 80));
        b.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
        b.setBackground(Ui.ripple(this, R.color.press_dark, Ui.color(this, R.color.card),
                Ui.color(this, R.color.text_black), 20));
        b.setContentDescription(label);
        b.setOnClickListener(click);
        ContactsActivity.asButton(b, null);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Ui.dp(this, 32);
        b.addView(icon, new LinearLayout.LayoutParams(size, size));
        TextView t = Ui.bold(this, label, Ui.TEXT_BUTTON);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(14, Ui.TEXT_BUTTON, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        b.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (topBar.getChildCount() > 0) lp.setMarginStart(Ui.dp(this, 8));
        topBar.addView(b, lp);
    }

    // ---------------------------------------------------------------- body

    private void show(ContactInfo c) {
        content.removeAllViews();
        String name = ContactsActivity.displayName(this, c);

        ContactsActivity.Avatar avatar = new ContactsActivity.Avatar(this, AVATAR_DP);
        avatar.bind(c, true);
        int size = Ui.dp(this, AVATAR_DP);
        content.addView(avatar.view, new LinearLayout.LayoutParams(size, size));

        TextView title = Ui.bold(this, name, 32);
        title.setGravity(Gravity.CENTER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) title.setAccessibilityHeading(true);
        content.addView(title, Ui.fullWidth(this, 16));

        if (c.starred) {
            TextView fav = Ui.text(this, getString(R.string.contacts_is_favourite), Ui.TEXT_BODY);
            fav.setTextColor(Ui.color(this, R.color.text_muted));
            fav.setGravity(Gravity.CENTER);
            content.addView(fav, Ui.fullWidth(this, 4));
        }

        if (c.phones.isEmpty()) {
            TextView none = Ui.text(this, getString(R.string.contacts_no_number), Ui.TEXT_BODY);
            none.setTextColor(Ui.color(this, R.color.text_muted));
            none.setGravity(Gravity.CENTER);
            content.addView(none, Ui.fullWidth(this, 32));
            return;
        }
        boolean several = c.phones.size() > 1;
        for (ContactInfo.Phone ph : c.phones) {
            String type = typeLabel(ph);
            Button call = Ui.button(this, several
                    ? getString(R.string.contacts_call_type, getString(R.string.call), type)
                    : getString(R.string.call), R.color.green);
            ContactsActivity.setIcon(call, R.drawable.ic_contacts_call, Palette.ON_COLOR);
            call.setMinHeight(Ui.dp(this, 80));
            call.setContentDescription(several
                    ? getString(R.string.contacts_call_type_cd, name, type)
                    : getString(R.string.contacts_call_cd, name));
            call.setOnClickListener(v -> askCall(name, several ? type : null, ph.number));
            content.addView(call, Ui.fullWidth(this, several ? 28 : 32));

            TextView label = Ui.text(this, several ? type : getString(R.string.contacts_number),
                    Ui.TEXT_BODY);
            label.setTextColor(Ui.color(this, R.color.text_muted));
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            content.addView(label, Ui.fullWidth(this, 12));

            TextView number = Ui.bold(this, ph.number, 30);
            number.setGravity(Gravity.CENTER);
            number.setTextIsSelectable(false);
            // Read digit by digit, not as "seventeen billion ...".
            number.setContentDescription(PhoneNumberUtils.createTtsSpannable(ph.number));
            content.addView(number, Ui.fullWidth(this, 0));
        }
    }

    /** "Mobile", "Home", ... in the phone's language; the user's own label for custom types. */
    private String typeLabel(ContactInfo.Phone ph) {
        CharSequence s = Phone.getTypeLabel(getResources(), ph.type, ph.label);
        return s == null || s.length() == 0 ? getString(R.string.contacts_number) : s.toString();
    }

    private void askCall(String name, String type, String number) {
        dismissDialog();
        String question = type == null ? getString(R.string.contacts_call_question, name)
                : getString(R.string.contacts_call_question_type, name, type);
        dialog = Ui.confirm(this, question, getString(R.string.call), R.color.green,
                () -> Calls.place(this, number));
    }

    private void askDelete() {
        if (store.locked() || contact == null) return;
        dismissDialog();
        String name = ContactsActivity.displayName(this, contact);
        dialog = Ui.confirm(this, getString(R.string.contacts_delete_question, name),
                getString(R.string.contacts_delete), R.color.red, () -> {
                    ContentResolver cr = getContentResolver();
                    long gone = id;
                    Contacts.BACKGROUND.execute(() -> {
                        boolean ok = Contacts.delete(cr, gone);
                        Contacts.MAIN.post(() -> {
                            if (isDestroyed()) return;
                            if (ok) {
                                finish();
                            } else {
                                Toast.makeText(this, R.string.contacts_delete_failed,
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    });
                });
    }

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }
}
