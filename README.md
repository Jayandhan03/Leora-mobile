# Leora Mobile

Native Android shell (Capacitor) around the live Leora web app at
`https://yournews-three.vercel.app`. There is no separate UI here — the
WebView loads the production site directly, so every feature (dashboard,
delivery, agents, etc.) is identical to the web app by construction, and it
updates automatically whenever the web app is redeployed. Nothing in this
repo needs to change when you ship new features to the web app — only when
the native shell itself needs updating (icons, permissions, app version).

## Status

- Build toolchain installed and working (JDK 21 + Android SDK command-line
  tools, no Android Studio GUI needed — see below).
- Debug and signed release builds both succeed.
- App icon and splash screen generated from the real brand mark
  (`app/icon.svg` in the main repo, `#0BA678`).
- A signed release APK is already built: **`releases/leora-v1.0.apk`**.
- Google Sign-In uses the OS-level account picker (see below) — this
  **requires an Android OAuth client to be registered in Google Cloud
  Console** (package `com.leora.app` + the release/debug SHA-1 fingerprints).
  If sign-in fails with something like `DEVELOPER_ERROR` or `10:`, that
  registration is missing or the SHA-1 doesn't match the APK you're testing.
- **Not yet verified on a real device** — this dev environment has no
  connected Android device and no working emulator (it's already running
  inside a VM without nested-virtualization acceleration, so an emulator
  would be unusably slow or fail to boot). See "Testing on a physical device"
  below.
- **The backend needs to be deployed.** `app/api/auth/mobile-google` lives in
  the main `D:\Leora` repo — push/deploy that repo to Vercel before testing
  sign-in, or the app has nothing to exchange the ID token with.

## How Google Sign-In works here

The app uses the OS-level "choose a Google account" sheet — the same native
picker you'd see in any Android app — listing every account added on the
device (Settings > Accounts), not just whatever's signed into a browser.

Flow:
1. User taps "Continue with Google" → `GoogleAuth.signIn()`
   (`@southdevs/capacitor-google-auth`, backed by Google Play Services) shows
   the native account picker and returns an ID token for whichever account
   was picked.
2. The app POSTs that ID token to `/api/auth/mobile-google` (in the main
   repo).
3. That route verifies the token with Google, upserts the same Mongo `User`
   record the web login uses, and sets the same NextAuth session cookie —
   `sub` is set to Google's own account id (`payload.sub`), matching exactly
   what the web OAuth flow puts in the JWT. Same account, same session
   mechanism as web, nothing synced or duplicated.
4. The app hard-navigates to `/dashboard`, now signed in.

This replaced an earlier Custom-Tab-based approach (open a system browser tab,
bridge the session back via a `leora://` deep link) that technically worked
but only ever showed whatever account the browser already had a session for,
with no way to pick a different one. The native picker was the actual ask.

### Required: register an Android OAuth client

Play Services checks the app's package name + signing certificate against a
registered Android OAuth client before it'll hand back a token — this is
**in addition to**, not instead of, the existing Web OAuth client (the app
still authenticates as that Web client; this entry just vouches for the app
itself). Nothing from it needs to go into code or config.

In Google Cloud Console → APIs & Services → Credentials (same project as the
existing Web client) → **Create Credentials → OAuth client ID → Android**:

| Field | Value |
|---|---|
| Package name | `com.leora.app` |
| SHA-1 (release) | `ED:6A:45:FD:A9:1F:94:EB:39:73:7E:A0:74:F6:8D:C1:44:FF:D2:92` |
| SHA-1 (debug, optional) | `A5:9D:83:2E:A1:3C:07:DA:73:D6:4E:6D:18:50:31:DA:54:E7:78:B2` |

Add the debug entry too if you ever test a `gradlew assembleDebug` build
instead of the release one — different signing key, different fingerprint.

If you ever regenerate the release keystore, get the new SHA-1 with:
```bash
& "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\keytool.exe" -list -v -keystore D:\Android\keystore\leora-release.keystore -alias leora
```

## Build toolchain (already installed on this machine)

No Android Studio GUI was installed — just the pieces that actually compile
and sign the app, all scriptable from a terminal:

| Tool | Location |
|---|---|
| JDK 21 (Temurin) | `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` |
| Android SDK | `D:\Android\Sdk` (platform-tools, `platforms;android-36`, `build-tools;36.0.0`) |
| Gradle cache | `D:\Android\gradle-home` (redirected off C: on purpose — it only has 24GB free) |
| Release keystore | `D:\Android\keystore\leora-release.keystore` |

`JAVA_HOME`, `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `GRADLE_USER_HOME` are set as
User environment variables, so **new** terminal windows pick them up
automatically. If you want a full Android Studio install later (for the
visual layout inspector, logcat UI, etc.) you can still install it — point it
at the same `D:\Android\Sdk` and it'll reuse everything here instead of
downloading its own copy.

## Rebuilding

```bash
cd D:\Leora-mobile
npx cap sync android          # after changing capacitor.config.json or plugins
cd android
.\gradlew.bat assembleDebug   # unsigned debug build -> app\build\outputs\apk\debug\
.\gradlew.bat assembleRelease # signed release build  -> app\build\outputs\apk\release\
```

If you change the production domain later, update `capacitor.config.json` at
the repo root and re-run `npx cap sync android` before rebuilding. The same
file also holds the `GoogleAuth.clientId` (your Web OAuth client ID) used for
native sign-in.

## The signing keystore — read this before you lose it

`D:\Android\keystore\leora-release.keystore`, alias `leora`, password
`LeoraMobile2026!` (also in `android/key.properties`, which is git-ignored).

**Back up the keystore file and password somewhere outside this machine** —
a password manager or encrypted cloud storage. If you lose it, you can never
publish an update under `com.leora.app` again; Play Store and Android itself
both require every future version to be signed with the same key.

## Testing on a physical Android device

1. On the phone: Settings → About phone → tap "Build number" 7 times to
   enable Developer Options, then enable **USB debugging** under Developer
   Options.
2. Connect the phone via USB, accept the "Allow USB debugging?" prompt.
3. From this machine:
   ```bash
   cd D:\Leora-mobile
   & "D:\Android\Sdk\platform-tools\adb.exe" devices   # confirm the phone shows up
   & "D:\Android\Sdk\platform-tools\adb.exe" install -r releases\leora-v1.0.apk
   ```
   (`-r` reinstalls over the previous copy so you don't have to uninstall
   first when testing a new build.)
4. Open the "Leora" app on the phone, tap "Continue with Google", confirm the
   native account picker shows up (not a browser tab), pick an account, and
   confirm it lands on `/dashboard` inside the app already signed in.

No USB cable handy? Copy `releases\leora-v1.0.apk` to the phone any other way
(email it to yourself, cloud drive, etc.) and open it from a file manager —
Android will prompt to allow installing from that source on first try.

## Distribution

Upload `releases/leora-v1.0.apk` to your website and link it from a
"Download for Android" button. Android will warn users about installing from
an unknown source on first install — expected until this is distributed
through Play Store. Note Play Store's own "Play App Signing" re-signs the app
with a Google-managed key once you publish there, which changes the SHA-1 —
you'll need to add that new fingerprint (found in Play Console after upload)
to the same Android OAuth client when that day comes.

## Not done yet (intentionally out of scope for the first APK)

- Verifying the login flow actually works end-to-end on a real device (see
  above — needs your phone, this environment can't do it).
- Deploying `app/api/auth/mobile-google` to Vercel.
- Registering the Android OAuth client in Google Cloud Console (see above —
  needs your Google account, can't be done from here).
- iOS build (needs a Mac or cloud macOS CI — revisit once Android is
  validated). `@southdevs/capacitor-google-auth` supports iOS too, so the
  native-picker approach carries over without another rewrite.
- Push notifications.
- Play Store listing (privacy policy, content rating, staged rollout).
