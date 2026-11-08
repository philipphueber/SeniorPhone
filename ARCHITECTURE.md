# Architecture

Package `app.phueber.seniorphone`. The phone and the address book for seniors:
a contact list with big names and favourites at the top, a number pad, and its
own big incoming-call and in-call screens. It takes Android's phone-app role
(DIALER), which is what lets it show those call screens.

Two icons on the home screen, because seniors look for "Phone" and
"Contacts" separately: **Phone** (`DialActivity`) opens the number pad,
**Contacts** (`ContactsActivity`, also `APP_CONTACTS`) the contact list. The
number pad has its own task affinity, so each icon always opens its own screen;
opened from the contact list it stays in the list's task, and Back returns
there.

Contacts and favourites are not stored by the app. They are the phone's own
address book (`ContactsContract`, favourite = `STARRED`), so they are the same
in every app and survive a reinstall. The app stores only its lock flag and
colour theme (`Store`).

## Files

| Class | Role |
|---|---|
| `ContactsActivity` | The first screen: Number pad and Settings (or, locked, a padlock), search, Favourites, then everyone A–Z with letter headers. A ListView, because an address book can hold hundreds of people. "Back to the call" banner while a call is on. |
| `ContactActivity` | One person: Back · Edit · Delete (locked: only Back), big picture, name, one green Call per number, which asks "Call Maria Schmidt?" first. |
| `EditContactActivity` | Our own simple form: first name, last name, one number. Not available when locked. |
| `Contacts` | Address-book access on one background thread (so a star or a delete always finishes before the reload after it). |
| `ContactInfo`, `ContactList`, `ContactForm` | Pure: the contact value, sorting and sections (Ä under A, Favourites first), search key, form rules. Unit tested. |
| `DialActivity` | The number pad; also what other apps open for "dial this number". |
| `DialNumber` | Pure: what may be typed and how a number from another app is cleaned. Unit tested. |
| `Calls` | `place()`: places a call through Telecom, asking for the call permission if needed. |
| `CallService` | The `InCallService` Telecom binds while there is a call. The only owner of call state; answer, decline, hang up, speaker. |
| `CallActivity` | The call screen: incoming (Answer / Decline), calling and connected (Hang up, Speaker, Louder), ended. Over the lock screen. |
| `CallNotifier` | The incoming-call notification (full screen, Answer / Decline) and the ongoing one (Hang up). |
| `CallActionReceiver` | Decline and Hang up from the notifications. |
| `CallState` | Pure: Telecom states to screens, which call gets the screen, why a call ended, the timer text. Unit tested. |
| `Role` | Asks once per start to become the phone app; Settings shows the state. |
| `SettingsActivity` | Lock (long press), Colours, Phone app, phone settings. |
| `Ui`, `Palette` | Copied from SeniorLauncher: shared buttons, screens and dialogs; the four colour themes, contrast-tested. |
| `Store` | Own lock flag and theme; `locked()` is the launcher's lock or our own. |
| `LauncherLock` | Reads SeniorLauncher's lock; when the launcher cannot answer, the last known value is used (while it is installed). |

## A call can always be answered, declined and ended

This is the part that must never fail.

- Telecom binds `CallService` for every call because we hold the DIALER role,
  and starts it again after the process was killed. `onCallAdded` opens
  `CallActivity` directly; a bound InCallService may do that from the
  background, so the screen appears even with notifications switched off.
- `CallActivity` is `showWhenLocked` and `turnScreenOn`. It keeps no call
  state of its own: it reads `CallService` on every change and every second,
  so a recreated screen or a call that ended in the background never leaves a
  stale screen.
- The incoming notification has Answer and Decline buttons and a full-screen
  intent, as the fallback when the screen cannot be opened directly. Once our
  screen is in front, the notification turns quiet, so a heads-up never covers
  the caller's name.
- The ongoing notification has Hang up and comes back if swiped away.
- Ringing stays with the system (no `IN_CALL_SERVICE_RINGING`): the phone rings
  with its normal ringtone and volume.
- Answer is huge and on top, Decline smaller and below: declining by mistake
  is worse than answering by mistake, and stacked buttons cannot be hit by a
  sideways slip. New screens ignore taps for 0.7 s, so a tap meant for what
  was there before never lands on Hang up.
- Back does nothing during a call. While a call is on, the contact list shows
  a green "Back to the call".
- The proximity wake lock turns the screen off at the ear, only for earpiece
  calls, never while ringing.

## Lock

One lock for the whole phone. With SeniorLauncher installed, this app follows
the home screen's lock (`LauncherLock` reads the launcher's read-only
`LockProvider`; a ContentObserver follows changes while the list is open).
Settings then only explains that, and holding the padlock here says to unlock
on the home screen: this app can see the lock but never lift it. Without the
launcher, Settings → Lock (long press) is this app's own lock, unlocked by
holding the padlock.

Locked: Settings is replaced by a padlock, the star buttons go, a favourite shows its
star inside its pill on the right, and New contact, Edit and Delete
disappear. Calling works as always. No PIN, on purpose: it protects against
accidents.

Every screen has its Back button at the bottom, like the other Senior apps.

## Permissions

- Contacts (read, write), phone (call, phone state, call log): what a phone
  app needs. Becoming the phone app usually grants them.
- Notifications and full-screen intent: the incoming-call notification. Calls
  still work without them.
- `android.hardware.telephony` is required: without a phone line there is
  nothing to do.
