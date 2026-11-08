# Contributing

How SeniorPhone is built and what a change has to respect. How the code fits
together is in [ARCHITECTURE.md](ARCHITECTURE.md).

## Ground rules

- **Simplicity is the product.** SeniorPhone is the phone and the address
  book: contacts with big names, favourites at the top, a number pad, and its
  own big incoming-call and in-call screens. No messages, no call blocking
  lists, no video calls. A pull request that adds a feature will most likely be
  declined; one that removes complexity is welcome.
- **A call can always be answered, declined and ended.** Every change to
  `CallService`, `CallActivity`, `CallNotifier` or `CallActionReceiver` must
  keep those three working from the lock screen, with the screen off and with
  notifications turned off. See ARCHITECTURE.md.
- **No dependencies.** Plain Java against the Android platform APIs. No
  AndroidX, Kotlin, Compose or third-party libraries. `testImplementation` of
  JUnit is the only exception.
- **English and German.** Every user-visible string goes in both
  `res/values/strings.xml` and `res/values-de/strings.xml` (formal "Sie").
- **Big and plain.** Large text, large touch targets, high contrast, no
  gestures.

## Commits

- One coherent change per commit. The subject says what changed, in the
  imperative, without a trailing period ("Add a Back button that goes to the
  home screen").
- The body says *why* this is the right change: what was tried, what was tested
  on which device, what was ruled out. That reasoning is not recoverable from
  the diff.
- Changes to ARCHITECTURE.md, CONTRIBUTING.md and the README go in the same
  commit as the code they describe.
- A release is its own commit, `Release <version>`, containing only the version
  bump.
- Never force-push or rewrite `main`.

## Building

Needs JDK 17 and the Android SDK (API 35). Point Gradle at the SDK with a
gitignored `local.properties` containing `sdk.dir=/path/to/Android/Sdk`.

    ./gradlew assembleDebug     # app/build/outputs/apk/debug/SeniorPhone-<version>-debug.apk
    ./gradlew assembleRelease   # signed only if signing is configured, see below

## Testing

Unit tests carry the weight; device testing is kept minimal.

    ./gradlew test        # JVM unit tests
    ./gradlew lint
    scripts/smoke.sh      # on an emulator: contacts, a call out, a call in

Logic that can be tested without a device belongs in a pure Java class (no
`android.*` imports) with a JVM test.

## Release signing

Maintainers only. Run once:

    scripts/setup-signing.sh            # random password, straight into the keyring
    scripts/setup-signing.sh --prompt   # or type your own

It creates `~/keys/seniorphone-release.jks` (outside the repo), stores the
password in the system keyring (`secret-tool lookup service seniorphone key
release-keystore`) and writes the gitignored `keystore.properties`. It refuses
to replace an existing key: Android only accepts an update signed by the same
key, so a new key would strand everyone who installed the app. Back up the
keystore and the password off the machine. Without any of this the release
build is unsigned; debug builds and tests are unaffected.

## Releasing

1. Bump `appVersionName` and `appVersionCode` in `app/build.gradle.kts`.
   The version code must always go up, or phones refuse the update.
2. `./gradlew test lint assembleRelease`
3. Check that the output is `app/build/outputs/apk/release/SeniorPhone-<version>-release.apk`.
   An `-unsigned` name means signing was not configured.
4. Verify the signature with `apksigner verify --print-certs <apk>` against the
   release certificate below. A different certificate means the wrong key: do
   not publish.
5. Commit as `Release <version>`, tag `v<version>` and attach the APK to a
   GitHub release, renamed to `SeniorPhone-<version>.apk`. 0.0.x versions are
   pre-releases. The release notes are for the people using the app: what
   changed for them, in plain words, in English and German. Technical detail
   (signing, checksums, internals) stays in this file and in ARCHITECTURE.md;
   the same goes for the README.

### Release certificate

TODO: after `scripts/setup-signing.sh`, put the `CN=SeniorPhone, C=DE`
certificate's SHA-256 digest here, exactly as `apksigner` prints it.
