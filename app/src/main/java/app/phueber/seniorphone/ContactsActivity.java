package app.phueber.seniorphone;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the senior sees first: every contact by name, favourites on top, then
 * A–Z under letter headers, with a search field (Bleta's contacts screen).
 * A tap on a name opens the contact page, which has the Call button.
 *
 * The helper sets the favourites with the star on the left of each row and
 * then locks: locked, the star buttons go and a favourite shows its star inside
 * its pill on the right, and "New
 * contact", Edit and Delete are gone, so nothing can be changed by accident.
 *
 * The list is a ListView, not a column in a ScrollView as on the launcher's
 * apps screen: an address book can hold hundreds of people, and recycled rows
 * keep both start-up and every reload (the address book changes whenever an
 * account syncs) cheap.
 */
public final class ContactsActivity extends Activity {
    private static final String[] PERMISSIONS = {
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS };
    /** Not Role.REQUEST_CODE (7301), which shares this activity. */
    private static final int REQUEST_CONTACTS = 7401;
    private static final int REQUEST_NOTIFICATIONS = 7403;
    private Button callBanner;
    /** A sync changes many rows in a burst; reload once it has settled. */
    private static final long RELOAD_DELAY_MS = 400;
    private static final String STATE_ASKED = "asked_permission";
    private static final int ROW_MIN_DP = 80;
    private static final int STAR_DP = 72;
    private static final int AVATAR_DP = 56;

    private final Adapter adapter = new Adapter();
    private final Runnable reload = this::load;
    private Store store;
    private Palette.Theme shownTheme;
    private boolean locked;
    private Dialog dialog;

    private LinearLayout helperBar;
    private EditText search;
    private ListView listView;
    private TextView status;
    private LinearLayout permissionPanel;
    private TextView permissionText;
    private Button permissionButton;
    private Button newContact;

    /** Sorted contacts of the last load; the search filters these without reloading. */
    private List<ContactInfo> sorted = Collections.emptyList();
    private boolean loaded;
    /** Counts loads, so a slow older load cannot replace a newer result. */
    private int generation;
    /** The permission is asked by itself once per visit; after that only via the button. */
    private boolean askedPermission;
    private ContentObserver observer;
    private android.app.Dialog roleDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shownTheme = Ui.applyTheme(this);
        store = new Store(this);
        locked = store.locked();
        askedPermission = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_ASKED, false);
        // The keyboard only when the search field is tapped, and the list shrinks
        // above it instead of being covered.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        // fitsSystemWindows on a frame of its own: on the padded page itself it would
        // replace the side padding with the (zero) side insets.
        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        frame.addView(build());
        setContentView(frame);
        roleDialog = Role.askIfNeeded(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_ASKED, askedPermission); // no second question after a colour change
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // The app icon tapped again: start from the top, like a fresh start.
        if (search != null && search.length() > 0) search.setText("");
        if (listView != null) listView.setSelection(0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        observe();
        // The helper may lock or unlock the home screen while we are open
        // (split screen, or quick switching): follow at once.
        try {
            getContentResolver().registerContentObserver(LauncherLock.STATE, false, lockObserver);
        } catch (RuntimeException ignored) {
            // no launcher: our own lock only, checked in onResume
        }
    }

    private final ContentObserver lockObserver = new ContentObserver(Contacts.MAIN) {
        @Override public void onChange(boolean selfChange) {
            boolean nowLocked = store.locked();
            if (nowLocked == locked) return;
            locked = nowLocked;
            dismissDialog();
            renderLock();
            showContent();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (store.theme() != shownTheme) {
            recreate(); // the helper picked other colours in Settings
            return;
        }
        boolean nowLocked = store.locked();
        if (nowLocked != locked || helperBar.getChildCount() == 0) {
            locked = nowLocked;
            renderLock();
        }
        callBanner.setVisibility(CallService.hasLiveCall() ? View.VISIBLE : View.GONE);
        showContent();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (observer != null) {
            getContentResolver().unregisterContentObserver(observer);
            observer = null;
        }
        Contacts.MAIN.removeCallbacks(reload);
        getContentResolver().unregisterContentObserver(lockObserver);
        dismissDialog();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Role.dismiss(this, roleDialog);
        generation++; // drop results still on their way
    }

    /**
     * The permission question waits until our window has the focus: if Role
     * has just put up "Make this the phone app?", the system dialog would
     * otherwise cover it. Becoming the phone app also grants the contacts
     * permission, so after that there is often nothing left to ask.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !askedPermission && !Contacts.canRead(this)) {
            askedPermission = true;
            requestPermissions(PERMISSIONS, REQUEST_CONTACTS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CONTACTS) return;
        observe();
        showContent();
        // Then, once, notifications (Android 13+): the incoming-call notification
        // and the "back to the call" one. Calls still work without them.
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    // ---------------------------------------------------------------- layout

    private View build() {
        int p = Ui.dp(this, 16);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(p, 0, p, Ui.dp(this, 12));

        TextView title = Ui.bold(this, getString(R.string.contacts_title), Ui.TEXT_TITLE);
        title.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), 0, Ui.dp(this, 8));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) title.setAccessibilityHeading(true);
        page.addView(title);

        // While a call is on (the senior pressed Home during it), the way back is
        // right on top: notifications may be off, and then this is the only one.
        callBanner = Ui.button(this, getString(R.string.contacts_back_to_call), R.color.green);
        callBanner.setOnClickListener(v -> CallActivity.open(this));
        callBanner.setVisibility(View.GONE);
        page.addView(callBanner, Ui.fullWidth(this, 0));

        helperBar = new LinearLayout(this);
        helperBar.setOrientation(LinearLayout.HORIZONTAL);
        helperBar.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(helperBar, Ui.fullWidth(this, 0));

        search = searchField();
        page.addView(search, Ui.fullWidth(this, 12));

        FrameLayout middle = new FrameLayout(this);
        listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setItemsCanFocus(true); // the star and the name are separate buttons
        listView.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setClipToPadding(false);
        listView.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        middle.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = Ui.text(this, getString(R.string.contacts_loading), 24);
        status.setTextColor(Ui.color(this, R.color.text_muted));
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 32);
        middle.addView(status, slp);

        permissionPanel = permissionPanel();
        middle.addView(permissionPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(middle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // For the helper, so outlined rather than a loud green.
        newContact = Ui.outlined(this, getString(R.string.contacts_new));
        setIcon(newContact, R.drawable.ic_contacts_person_add, Ui.color(this, R.color.text_black));
        newContact.setOnClickListener(v -> {
            if (!store.locked()) startActivity(new Intent(this, EditContactActivity.class));
        });
        page.addView(newContact, Ui.fullWidth(this, 8));
        // Back at the bottom, like every Senior app: many seniors do not know the
        // system back gesture. Here it leaves the app.
        page.addView(Ui.backButton(this), Ui.fullWidth(this, 8));
        return page;
    }

    /** The explanation that replaces the list while the contacts permission is missing. */
    private LinearLayout permissionPanel() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 24), 0, 0);
        TextView head = Ui.bold(this, getString(R.string.contacts_permission_title), 24);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) head.setAccessibilityHeading(true);
        box.addView(head);
        permissionText = Ui.text(this, "", Ui.TEXT_BODY);
        box.addView(permissionText, Ui.fullWidth(this, 12));
        permissionButton = Ui.button(this, "", R.color.green);
        permissionButton.setOnClickListener(v -> {
            if (canAskAgain()) {
                askedPermission = true;
                requestPermissions(PERMISSIONS, REQUEST_CONTACTS);
            } else {
                openAppSettings();
            }
        });
        box.addView(permissionButton, Ui.fullWidth(this, 24));
        box.setVisibility(View.GONE);
        return box;
    }

    /**
     * Whether the system will still show its question. After "Don't allow"
     * twice (or "Don't ask again" on older Android) it answers no by itself,
     * and only the app's page in the phone settings can grant access.
     */
    private boolean canAskAgain() {
        return !askedPermission
                || shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS);
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (RuntimeException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                // Nothing left to open.
            }
        }
    }

    /** Number pad, and Settings or (locked) the padlock. */
    private void renderLock() {
        helperBar.removeAllViews();
        addHelper(R.drawable.ic_contacts_dialpad, R.string.contacts_dialpad, DialActivity.class);
        if (locked) {
            helperBar.addView(padlock());
        } else {
            addHelper(R.drawable.ic_contacts_settings, R.string.contacts_settings,
                    SettingsActivity.class);
        }
        newContact.setVisibility(locked ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged(); // stars clickable or not
    }

    /** Icon above a word, outlined, like the launcher's helper bar. */
    private void addHelper(int iconRes, int labelRes, Class<? extends Activity> target) {
        int fg = Ui.color(this, R.color.text_black);
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(Ui.dp(this, Ui.BUTTON_HEIGHT));
        b.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
        b.setBackground(Ui.ripple(this, R.color.press_dark, Ui.color(this, R.color.card), fg, 20));
        String label = getString(labelRes);
        b.setContentDescription(label);
        b.setOnClickListener(v -> {
            try {
                startActivity(new Intent(this, target));
            } catch (RuntimeException ignored) {
                // Cannot happen for our own screens; never crash the main screen.
            }
        });
        asButton(b, null);

        TextView t = Ui.bold(this, label, 20);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(1);
        t.setAutoSizeTextTypeUniformWithConfiguration(14, 20, 1, TypedValue.COMPLEX_UNIT_SP);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(fg));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Ui.dp(this, 30);
        b.addView(icon, new LinearLayout.LayoutParams(size, size));
        b.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (helperBar.getChildCount() > 0) lp.setMarginStart(Ui.dp(this, 10));
        helperBar.addView(b, lp);
    }

    /**
     * Small and muted on purpose: it is for the helper, not the senior. A short
     * tap does nothing; only a long press offers to unlock.
     */
    private View padlock() {
        ImageView lock = new ImageView(this);
        lock.setImageResource(R.drawable.ic_contacts_lock);
        lock.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_muted)));
        lock.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = Ui.dp(this, 16);
        lock.setPadding(pad, pad, pad, pad);
        lock.setBackground(Ui.ripple(this, R.color.press_dark, Color.TRANSPARENT, 0, 32));
        lock.setContentDescription(getString(R.string.contacts_locked_desc));
        lock.setFocusable(true);
        lock.setLongClickable(true);
        lock.setOnLongClickListener(v -> {
            dismissDialog();
            if (store.lockedByLauncher()) {
                // Locked together with the home screen: only the home screen
                // unlocks, so this app cannot lift the helper's lock.
                dialog = Ui.confirm(this, getString(R.string.contacts_locked_by_launcher),
                        getString(R.string.contacts_go_home), R.color.green, () ->
                                startActivity(new Intent(Intent.ACTION_MAIN)
                                        .addCategory(Intent.CATEGORY_HOME)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                return true;
            }
            dialog = Ui.confirm(this, getString(R.string.contacts_unlock_question),
                    getString(R.string.contacts_unlock), R.color.green, () -> {
                        store.setLocked(false);
                        locked = false;
                        renderLock();
                    });
            return true;
        });
        lock.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        getString(R.string.contacts_unlock)));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Ui.dp(this, Ui.BUTTON_HEIGHT + 8), Ui.dp(this, Ui.BUTTON_HEIGHT + 8));
        lp.setMarginStart(Ui.dp(this, 10));
        lock.setLayoutParams(lp);
        return lock;
    }

    private EditText searchField() {
        EditText s = new EditText(this);
        s.setHint(R.string.contacts_search_hint);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.TEXT_BUTTON);
        s.setTextColor(Ui.color(this, R.color.text_black));
        s.setHintTextColor(Ui.color(this, R.color.text_muted));
        s.setSingleLine(true);
        s.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        s.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        s.setMinHeight(Ui.dp(this, Ui.BUTTON_HEIGHT));
        int p = Ui.dp(this, 18);
        s.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.color(this, R.color.card));
        bg.setStroke(Ui.dp(this, 2), Ui.color(this, R.color.text_black));
        bg.setCornerRadius(Ui.dp(this, 32));
        s.setBackground(bg);

        Drawable glass = getDrawable(R.drawable.ic_contacts_search);
        if (glass != null) {
            glass = glass.mutate();
            glass.setTint(Ui.color(this, R.color.text_black));
            int size = Ui.dp(this, 30);
            glass.setBounds(0, 0, size, size);
            s.setCompoundDrawablesRelative(glass, null, null, null);
            s.setCompoundDrawablePadding(Ui.dp(this, 12));
        }
        s.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable t) { refilter(); }
        });
        // "Search" on the keyboard closes it, so the results can be seen.
        s.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            return true;
        });
        return s;
    }

    // ---------------------------------------------------------------- data

    /** The list, or the explanation while we may not read the contacts. */
    private void showContent() {
        boolean allowed = Contacts.canRead(this);
        permissionPanel.setVisibility(allowed ? View.GONE : View.VISIBLE);
        search.setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (!allowed) {
            listView.setVisibility(View.GONE);
            status.setVisibility(View.GONE);
            boolean ask = canAskAgain();
            permissionText.setText(ask ? R.string.contacts_permission_text
                    : R.string.contacts_permission_settings_text);
            permissionButton.setText(ask ? R.string.contacts_permission_allow
                    : R.string.contacts_permission_settings);
            return;
        }
        refilter();
        load();
    }

    /**
     * Watches the whole address book: a sync, a contact added in another app,
     * or our own star and delete all end up here, and the list follows.
     */
    private void observe() {
        if (observer != null || !Contacts.canRead(this)) return;
        observer = new ContentObserver(Contacts.MAIN) {
            @Override public void onChange(boolean selfChange) {
                Contacts.MAIN.removeCallbacks(reload);
                Contacts.MAIN.postDelayed(reload, RELOAD_DELAY_MS);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    ContactsContract.Contacts.CONTENT_URI, true, observer);
        } catch (RuntimeException e) {
            observer = null; // onResume still reloads
        }
    }

    /** Reads and sorts on the background thread, then shows on the main thread. */
    private void load() {
        if (!Contacts.canRead(this)) return;
        int gen = ++generation;
        ContentResolver cr = getContentResolver();
        Contacts.BACKGROUND.execute(() -> {
            List<ContactInfo> list = Contacts.list(cr);
            // A new Collator each time: it is not thread-safe, and the phone's
            // language may have changed since the last load.
            ContactList.sort(list, Collator.getInstance());
            Contacts.MAIN.post(() -> {
                if (gen != generation || isDestroyed()) return;
                sorted = list;
                loaded = true;
                refilter();
            });
        });
    }

    private void refilter() {
        if (!Contacts.canRead(this)) return;
        String q = search.getText().toString();
        List<ContactList.Item> items = ContactList.items(sorted, q);
        adapter.set(items);
        listView.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        status.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (!loaded) {
            status.setText(R.string.contacts_loading);
        } else if (sorted.isEmpty()) {
            status.setText(locked ? R.string.contacts_none_locked : R.string.contacts_none);
        } else {
            status.setText(getString(R.string.contacts_no_match, q.trim()));
        }
    }

    private void toggleStar(ContactInfo c, ImageView star) {
        if (store.locked()) return;
        boolean to = !c.starred;
        if (!Contacts.canWrite(this)) {
            askedPermission = true;
            requestPermissions(PERMISSIONS, REQUEST_CONTACTS);
            return;
        }
        styleStar(star, to, true, c); // at once; the reload moves the row
        star.announceForAccessibility(getString(to ? R.string.contacts_starred_now
                : R.string.contacts_unstarred_now, displayName(this, c)));
        ContentResolver cr = getContentResolver();
        Contacts.BACKGROUND.execute(() -> {
            Contacts.setStarred(cr, c.id, to);
            Contacts.MAIN.post(() -> { if (!isDestroyed()) load(); });
        });
    }

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    // ---------------------------------------------------------------- rows

    private final class Adapter extends BaseAdapter {
        private List<ContactList.Item> items = Collections.emptyList();

        void set(List<ContactList.Item> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) {
            ContactList.Item i = items.get(position);
            return i.isHeader() ? -1 - position : i.contact.id;
        }
        @Override public boolean hasStableIds() { return false; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return items.get(position).isHeader() ? 0 : 1;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            ContactList.Item item = items.get(position);
            if (item.isHeader()) {
                TextView h = convert instanceof TextView ? (TextView) convert : header();
                bindHeader(h, item.header);
                return h;
            }
            RowHolder holder = convert != null && convert.getTag() instanceof RowHolder
                    ? (RowHolder) convert.getTag() : row();
            bindRow(holder, item.contact);
            return holder.root;
        }
    }

    /** Views of one contact row, kept for recycling. */
    private static final class RowHolder {
        LinearLayout root;
        ImageView star;
        LinearLayout card;
        /** Locked: the favourite's star, inside the pill on the right. */
        ImageView badge;
        Avatar avatar;
        TextView name;
        ContactInfo contact;
    }

    private TextView header() {
        TextView h = Ui.bold(this, "", 24);
        h.setPadding(Ui.dp(this, 8), Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 6));
        h.setCompoundDrawablePadding(Ui.dp(this, 10));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) h.setAccessibilityHeading(true);
        return h;
    }

    private void bindHeader(TextView h, String header) {
        boolean fav = ContactList.FAVOURITES.equals(header);
        h.setText(fav ? getString(R.string.contacts_favourites) : header);
        // Spoken "Other" rather than "number sign".
        h.setContentDescription(ContactList.OTHER.equals(header)
                ? getString(R.string.contacts_other_header_cd) : null);
        Drawable star = null;
        if (fav) {
            star = getDrawable(R.drawable.ic_contacts_star);
            if (star != null) {
                star = star.mutate();
                star.setTint(Ui.color(this, R.color.text_black));
                int size = Math.round(h.getTextSize() * 1.1f);
                star.setBounds(0, 0, size, size);
            }
        }
        h.setCompoundDrawablesRelative(star, null, null, null);
    }

    /**
     * Star on the left (Bleta), then avatar and name as one wide button. The
     * star is its own button with its own spoken name, so TalkBack users can
     * open a contact without changing favourites by accident.
     */
    private RowHolder row() {
        RowHolder h = new RowHolder();
        h.root = new LinearLayout(this);
        h.root.setOrientation(LinearLayout.HORIZONTAL);
        h.root.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        h.root.setTag(h);

        h.star = new ImageView(this);
        h.star.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int sp = Ui.dp(this, 18);
        h.star.setPadding(sp, sp, sp, sp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, STAR_DP), ViewGroup.LayoutParams.MATCH_PARENT);
        slp.setMarginEnd(Ui.dp(this, 8));
        h.root.addView(h.star, slp);

        h.card = new LinearLayout(this);
        h.card.setOrientation(LinearLayout.HORIZONTAL);
        h.card.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 12);
        h.card.setPadding(p, p, p, p);
        h.card.setMinimumHeight(Ui.dp(this, ROW_MIN_DP));
        h.card.setBackground(Ui.ripple(this, R.color.press_dark, Ui.color(this, R.color.card),
                Ui.color(this, R.color.card_border), 20));
        asButton(h.card, null);

        h.avatar = new Avatar(this, AVATAR_DP);
        int size = Ui.dp(this, AVATAR_DP);
        h.card.addView(h.avatar.view, new LinearLayout.LayoutParams(size, size));

        h.name = Ui.bold(this, "", Ui.TEXT_BUTTON);
        h.name.setMaxLines(2);
        h.name.setEllipsize(TextUtils.TruncateAt.END);
        h.name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        nlp.setMarginStart(Ui.dp(this, 14));
        h.card.addView(h.name, nlp);

        // Locked, the star moves into the pill, on the right: there is nothing to
        // tap any more, so it is part of the name, not a button beside it.
        h.badge = new ImageView(this);
        h.badge.setImageResource(R.drawable.ic_contacts_star);
        h.badge.setImageTintList(ColorStateList.valueOf(Ui.color(this, R.color.text_black)));
        h.badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // the row says "favourite"
        int bs = Ui.dp(this, 36);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(bs, bs);
        blp.setMarginStart(Ui.dp(this, 8));
        h.card.addView(h.badge, blp);
        h.root.addView(h.card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        h.card.setOnClickListener(v -> {
            if (h.contact == null) return;
            startActivity(new Intent(this, ContactActivity.class)
                    .putExtra(ContactActivity.EXTRA_ID, h.contact.id));
        });
        h.star.setOnClickListener(v -> { if (h.contact != null) toggleStar(h.contact, h.star); });
        return h;
    }

    private void bindRow(RowHolder h, ContactInfo c) {
        h.contact = c;
        String name = displayName(this, c);
        h.name.setText(name);
        h.card.setContentDescription(c.starred
                ? getString(R.string.contacts_row_favourite_cd, name) : name);
        h.avatar.bind(c, false);
        if (locked) {
            // Locked: no star button on the left (nothing to change, and no gap
            // for one); a favourite shows its star inside the pill instead.
            h.star.setVisibility(View.GONE);
            h.badge.setVisibility(c.starred ? View.VISIBLE : View.GONE);
        } else {
            h.badge.setVisibility(View.GONE);
            h.star.setVisibility(View.VISIBLE);
            styleStar(h.star, c.starred, true, c);
        }
    }

    /**
     * Filled star = favourite, outline = not. In the text colour rather than
     * gold: a yellow star on a white card is too faint to see. Unlocked it is a
     * button that says what it will do; locked it is only a picture (the row
     * itself already says "favourite").
     */
    private void styleStar(ImageView star, boolean starred, boolean clickable, ContactInfo c) {
        star.setImageResource(starred ? R.drawable.ic_contacts_star : R.drawable.ic_contacts_star_border);
        star.setImageTintList(ColorStateList.valueOf(Ui.color(this,
                starred || clickable ? R.color.text_black : R.color.text_muted)));
        star.setClickable(clickable);
        star.setFocusable(clickable);
        if (clickable) {
            star.setBackground(Ui.ripple(this, R.color.press_dark, Ui.color(this, R.color.card),
                    Ui.color(this, R.color.card_border), 20));
            star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            star.setContentDescription(getString(starred ? R.string.contacts_unstar_cd
                    : R.string.contacts_star_cd, displayName(this, c)));
            asButton(star, null);
        } else {
            star.setBackground(null);
            star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            star.setContentDescription(null);
        }
    }

    // ---------------------------------------------------------------- shared helpers

    /**
     * The name to show. The address book already falls back to the number or
     * e-mail for a contact without a name; this covers the rest.
     */
    static String displayName(Context c, ContactInfo info) {
        return info.name.isEmpty() ? c.getString(R.string.contacts_no_name) : info.name;
    }

    /** A solid button with an icon before the text, tinted to match the text. */
    static void setIcon(Button b, int iconRes, int tint) {
        Drawable d = b.getContext().getDrawable(iconRes);
        if (d == null) return;
        d = d.mutate();
        d.setTint(tint);
        int size = Math.round(b.getTextSize() * 1.2f);
        d.setBounds(0, 0, size, size);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(Ui.dp(b.getContext(), 12));
    }

    /** TalkBack announces the view as a button, and names its long press if any. */
    static void asButton(View v, CharSequence longClickLabel) {
        v.setClickable(true);
        v.setFocusable(true);
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                if (longClickLabel != null) {
                    info.addAction(new AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,
                            longClickLabel));
                }
            }
        });
    }

    /**
     * The round picture of a contact: the photo when there is one, else the
     * first letter on a coloured circle (a person symbol when the name has no
     * letter). Blue in the plain themes; in COLOURFUL each contact keeps its
     * own tile colour. White on both carries 4.5:1 (see Palette).
     *
     * Photos are decoded on {@link Contacts#BACKGROUND} and kept in a small
     * cache, so scrolling back up does not read them again. A recycled row may
     * have moved on to another contact by the time its photo arrives; the
     * wanted URI is checked before showing it.
     */
    static final class Avatar {
        /** About 4 MB of decoded photos: plenty for a screenful of rows and a contact page. */
        private static final LruCache<String, Bitmap> PHOTOS =
                new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                    @Override protected int sizeOf(String key, Bitmap value) {
                        return value.getByteCount();
                    }
                };
        /** Photos that failed to load: not tried again on every bind. */
        private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<>());

        final FrameLayout view;
        private final Context context;
        private final GradientDrawable circle = new GradientDrawable();
        private final TextView letter;
        private final ImageView person;
        private final ImageView photo;
        private final int sizePx;
        private String wanted;

        Avatar(Context c, int sizeDp) {
            context = c;
            sizePx = Ui.dp(c, sizeDp);
            view = new FrameLayout(c);
            circle.setShape(GradientDrawable.OVAL);
            view.setBackground(circle);
            view.setClipToOutline(true); // round photo without a library
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

            letter = new TextView(c);
            letter.setTextColor(Palette.ON_COLOR);
            letter.setTypeface(Typeface.DEFAULT_BOLD);
            letter.setGravity(Gravity.CENTER);
            letter.setIncludeFontPadding(false);
            // In px from the circle, not sp: the letter must fit the circle even
            // with the phone's largest font size.
            letter.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * 0.5f);
            view.addView(letter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            person = new ImageView(c);
            person.setImageResource(R.drawable.ic_contacts_person);
            person.setImageTintList(ColorStateList.valueOf(Palette.ON_COLOR));
            int pad = Math.round(sizePx * 0.18f);
            person.setPadding(pad, pad, pad, pad);
            view.addView(person, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            photo = new ImageView(c);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.addView(photo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        /** large: the contact page's big photo (full size) rather than the list thumbnail. */
        void bind(ContactInfo c, boolean large) {
            circle.setColor(Palette.colouredTiles(Ui.currentTheme(context))
                    ? Palette.tileColor(ContactList.colorIndex(c.id, Palette.tileColorCount()))
                    : Ui.color(context, R.color.blue));
            String initial = ContactList.initial(c.name);
            letter.setText(initial);
            letter.setVisibility(initial.isEmpty() ? View.GONE : View.VISIBLE);
            person.setVisibility(initial.isEmpty() ? View.VISIBLE : View.GONE);

            String uri = c.photoUri;
            wanted = uri;
            photo.setImageDrawable(null);
            photo.setVisibility(View.GONE);
            if (uri == null || MISSING.contains(uri)) return;
            String key = uri + (large ? "#large" : "");
            Bitmap cached = PHOTOS.get(key);
            if (cached != null) {
                show(cached);
                return;
            }
            ContentResolver cr = context.getContentResolver();
            int size = sizePx;
            Contacts.BACKGROUND.execute(() -> {
                Bitmap b = Contacts.photo(cr, uri, size);
                Contacts.MAIN.post(() -> {
                    if (b == null) {
                        MISSING.add(uri);
                        return;
                    }
                    PHOTOS.put(key, b);
                    if (uri.equals(wanted)) show(b);
                });
            });
        }

        private void show(Bitmap b) {
            photo.setImageBitmap(b);
            photo.setVisibility(View.VISIBLE);
        }
    }
}
