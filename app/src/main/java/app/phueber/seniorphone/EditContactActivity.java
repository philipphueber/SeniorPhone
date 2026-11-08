package app.phueber.seniorphone;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Our own small form for a new or changed contact: first name, last name, one
 * phone number, Save / Cancel. Deliberately not the phone's contact editor,
 * which has dozens of fields, accounts and small icons; the helper rarely
 * needs more than this, and everything the form does not show (other numbers,
 * e-mail, birthday, photo) is kept as it is.
 *
 * Save and Cancel are pinned below the fields, so they stay in sight above
 * the keyboard (the manifest sets adjustResize).
 *
 * Not available when locked: the screen closes at once.
 */
public final class EditContactActivity extends Activity {
    /** The contact to change (a long); absent for a new contact. */
    public static final String EXTRA_ID = "contact_id";
    private static final int REQUEST_WRITE = 7402;

    private Store store;
    private Contacts.Draft draft;
    private EditText first;
    private EditText last;
    private EditText number;
    private TextView more;
    private TextView error;
    private Button save;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.applyTheme(this);
        store = new Store(this);
        if (store.locked()) {
            finish();
            return;
        }
        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        boolean isNew = id < 0;

        int p = Ui.dp(this, 20);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(true);

        TextView title = Ui.bold(this, getString(isNew ? R.string.contacts_new_title
                : R.string.contacts_edit_title), Ui.TEXT_TITLE);
        title.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) title.setAccessibilityHeading(true);
        page.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(p, 0, p, Ui.dp(this, 16));
        scroll.addView(form);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        int nameType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS;
        first = field(form, R.string.contacts_first_name, nameType, EditorInfo.IME_ACTION_NEXT, 0);
        last = field(form, R.string.contacts_last_name, nameType, EditorInfo.IME_ACTION_NEXT, 20);
        number = field(form, R.string.contacts_phone_number, InputType.TYPE_CLASS_PHONE,
                EditorInfo.IME_ACTION_DONE, 20);
        number.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });

        more = Ui.text(this, "", Ui.TEXT_BODY);
        more.setTextColor(Ui.color(this, R.color.text_muted));
        more.setVisibility(View.GONE);
        form.addView(more, Ui.fullWidth(this, 12));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 12));
        error = Ui.bold(this, "", Ui.TEXT_BODY);
        error.setTextColor(Ui.color(this, R.color.red));
        error.setVisibility(View.GONE);
        // Spoken as soon as it appears, so a TalkBack user knows why nothing happened.
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bottom.addView(error, Ui.fullWidth(this, 0));
        save = Ui.button(this, getString(R.string.contacts_save), R.color.green);
        save.setOnClickListener(v -> save());
        bottom.addView(save, Ui.fullWidth(this, 8));
        Button cancel = Ui.button(this, getString(R.string.cancel), R.color.blue);
        cancel.setOnClickListener(v -> finish());
        bottom.addView(cancel, Ui.fullWidth(this, 12));
        page.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(page);

        if (isNew) {
            draft = new Contacts.Draft();
            first.requestFocus();
        } else {
            loadDraft(id);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store.locked()) finish();
    }

    /** A label above a big field; the label is tied to the field for TalkBack. */
    private EditText field(LinearLayout form, int labelRes, int inputType, int ime, int topDp) {
        TextView label = Ui.bold(this, getString(labelRes), Ui.TEXT_BODY);
        label.setId(View.generateViewId());
        form.addView(label, Ui.fullWidth(this, topDp));
        EditText e = new EditText(this);
        e.setId(View.generateViewId());
        label.setLabelFor(e.getId());
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.TEXT_BUTTON);
        e.setTextColor(Ui.color(this, R.color.text_black));
        e.setHintTextColor(Ui.color(this, R.color.text_muted));
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setImeOptions(ime);
        e.setMinHeight(Ui.dp(this, Ui.BUTTON_HEIGHT));
        int p = Ui.dp(this, 16);
        e.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.color.card));
        bg.setStroke(Ui.dp(this, 2), Ui.color(this, R.color.text_black));
        bg.setCornerRadius(Ui.dp(this, 16));
        e.setBackground(bg);
        form.addView(e, Ui.fullWidth(this, 6));
        return e;
    }

    /** Fills the form off the main thread; Save waits until it is there. */
    private void loadDraft(long id) {
        setFormEnabled(false);
        ContentResolver cr = getContentResolver();
        Contacts.BACKGROUND.execute(() -> {
            Contacts.Draft d = Contacts.loadDraft(cr, id);
            Contacts.MAIN.post(() -> {
                if (isDestroyed()) return;
                if (d == null) {
                    finish(); // deleted meanwhile
                    return;
                }
                draft = d;
                first.setText(d.first);
                last.setText(d.last);
                number.setText(d.number);
                if (d.otherNumbers > 0) {
                    more.setText(getResources().getQuantityString(
                            R.plurals.contacts_edit_more_numbers, d.otherNumbers, d.otherNumbers));
                    more.setVisibility(View.VISIBLE);
                }
                setFormEnabled(true);
            });
        });
    }

    private void setFormEnabled(boolean on) {
        first.setEnabled(on);
        last.setEnabled(on);
        number.setEnabled(on);
        Ui.setEnabled(save, on, R.color.green);
    }

    private void save() {
        if (draft == null || saving || store.locked()) return;
        String f = first.getText().toString();
        String l = last.getText().toString();
        String n = number.getText().toString();
        switch (ContactForm.check(f, l, n)) {
            case NO_NAME: showError(R.string.contacts_error_no_name, first); return;
            case NO_NUMBER: showError(R.string.contacts_error_no_number, number); return;
            case BAD_NUMBER: showError(R.string.contacts_error_bad_number, number); return;
            default: break;
        }
        if (!Contacts.canWrite(this)) {
            requestPermissions(new String[] { Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS }, REQUEST_WRITE);
            return;
        }
        saving = true;
        setFormEnabled(false);
        error.setVisibility(View.GONE);
        boolean isNew = draft.contactId < 0;
        Contacts.Draft d = draft;
        ContentResolver cr = getContentResolver();
        Contacts.BACKGROUND.execute(() -> {
            long id = Contacts.save(cr, d, f, l, n);
            Contacts.MAIN.post(() -> {
                if (isDestroyed()) return;
                saving = false;
                if (id < 0) {
                    setFormEnabled(true);
                    showError(R.string.contacts_error_save, null);
                    return;
                }
                // A new contact: show its page, so the helper sees it worked and
                // can call it to try. An edited one: back to its page, which reloads.
                if (isNew) {
                    startActivity(new Intent(this, ContactActivity.class)
                            .putExtra(ContactActivity.EXTRA_ID, id));
                }
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_WRITE) return;
        if (Contacts.canWrite(this)) save();
        else showError(R.string.contacts_error_permission, null);
    }

    private void showError(int res, EditText focus) {
        error.setText(res);
        error.setVisibility(View.VISIBLE);
        if (focus != null) focus.requestFocus();
    }
}
