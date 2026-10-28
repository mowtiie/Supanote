<div align="center">

<img src="metadata/en-US/images/icon.png" alt="Supanote icon" width="160" height="160">

# Supanote

**Your notes. Your server. Your rules.**

Supanote is a simple, open-source notes app that connects to your own Supabase backend instead of a company's servers. Whether you're using a free Supabase Cloud project or a self-hosted instance, your notes live where you decide.

![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)

</div>

## Features

* **Free and open source:** Licensed under the GPLv3.
* **Bring your own backend:** Connect to any Supabase project, Cloud or self-hosted, by entering your project URL and publishable key on first launch. Nothing is hardcoded or tied to one server, and you can switch servers anytime from Settings.
* **Private from other users:** Each account only ever sees its own notes, enforced by Row Level Security on the database itself.
* **Secure session storage:** Login sessions are kept in encrypted on-device storage, with automatic token refresh so you stay signed in.
* **Search and sorting:** Filter notes by title or content from the toolbar, and sort by newest, oldest, or title. Your sort choice is remembered. Pull down on the list to refresh.
* **Quick delete with undo:** Swipe a note away to delete it, and tap Undo within a few seconds to bring it back. Long-pressing a note (or the Delete action in TalkBack) asks for confirmation first.
* **Edited time:** Notes show when they were last edited (after you upgrade your database with the latest `schema.sql`).
* **Backup:** Export your notes to a JSON file and import them back in.
* **Dedicated editor:** A full-screen editor with a discard-changes safeguard so you never lose work by accident.
* **Appearance and privacy:** Light, dark, and battery-saver themes, three contrast levels, dynamic colors on supported devices, and an optional screen privacy mode that blocks screenshots and screen recordings.
* **Crash reports:** If the app hits an unexpected error, a dialog on the next launch lets you send the details by email or save them to a file. Nothing is sent automatically.

## Screenshots

<div align="center">
  <img src="metadata/en-US/images/phoneScreenshots/screenshot-1.jpg" alt="Screenshot 1" width="30%" />
  <img src="metadata/en-US/images/phoneScreenshots/screenshot-2.jpg" alt="Screenshot 2" width="30%" />
  <img src="metadata/en-US/images/phoneScreenshots/screenshot-3.jpg" alt="Screenshot 3" width="30%" />
  <img src="metadata/en-US/images/phoneScreenshots/screenshot-4.jpg" alt="Screenshot 4" width="30%" />
  <img src="metadata/en-US/images/phoneScreenshots/screenshot-5.jpg" alt="Screenshot 5" width="30%" />
  <img src="metadata/en-US/images/phoneScreenshots/screenshot-6.jpg" alt="Screenshot 6" width="30%" />
</div>

## Requirements

* Android 10 (API 29) or later.
* A Supabase backend: either a free [Supabase Cloud](https://supabase.com) project or your own self-hosted instance. The server must be reachable over **HTTPS**; plain `http://` URLs are rejected.

## Getting Started

Supanote needs a Supabase backend to talk to. Setting one up takes a few minutes:

1. Create a free project at [supabase.com](https://supabase.com) (or run your own self-hosted instance).
2. Open the **SQL Editor** and run [`schema.sql`](schema.sql) from this repo to create the `notes` table, its Row Level Security policies, and the edit-time trigger. The script is safe to run again, so run it again whenever you update Supanote to pick up database changes.
3. Under **Authentication → Sign In / Providers**, enable **Email**. For the easiest setup, turn off **Confirm email**. If you leave it on, new users must confirm their address by email before they can sign in, which requires working email delivery on your project.
4. Copy your **Project URL** and **publishable key**. The easiest place is your project's **Connect** dialog; the keys are also listed under **Settings → API Keys**. Legacy `anon` keys still work, but Supabase is deprecating them by the end of 2026, so prefer the publishable key (`sb_publishable_...`).
5. Open Supanote, paste in your URL and key on the setup screen, then create an account.

**Only use the publishable (or legacy anon) key.** Never paste a secret or `service_role` key into the app. It bypasses Row Level Security and would give anyone with the app full access to your database.

You can change servers anytime from **Settings → Change server**.

## Privacy and Limitations

* Supanote has no analytics or telemetry. It only talks to the server you configure.
* Notes are **not end-to-end encrypted**. They are stored in your database, so whoever runs the server can read them. Row Level Security only keeps other users out.
* Importing a backup always adds new notes. Importing the same file twice creates duplicates, and original creation dates are not preserved.
* Supanote hides its content from the Recents screen on Android 13 and later. Blocking screenshots and screen recordings is a separate **Screen privacy** setting that is off by default.
* Not included: offline mode, folders, tags, archive, trash, and end-to-end encryption.

## Verification

APK releases on GitHub are signed using my key. They can be verified using
[apksigner](https://developer.android.com/studio/command-line/apksigner.html#options-verify):

```
apksigner verify --print-certs --verbose supanote.apk
```

The output should report `Verifies`, with only the v2 scheme verified:

```
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
```

The certificate fingerprints should correspond to the ones listed below:

```
Owner: CN=Mowtiie
Issuer: CN=Mowtiie
Serial number: 8a256fdcdde50069
Valid from: Wed Jun 10 22:57:23 PST 2026 until: Sun Oct 26 22:57:23 PST 2053
Certificate fingerprints:
         SHA1: 56:4E:2C:DB:E4:06:C9:EC:15:E6:BC:D9:0A:88:38:72:8B:FB:13:20
         SHA256: 8B:67:51:F3:C3:31:85:63:5F:98:95:30:B6:C0:73:A1:39:7B:3D:41:2B:EF:AE:69:06:A2:EB:58:45:D2:DE:63
```

**Warning:** Only install Supanote APKs signed with the key above.

### PGP Signing

Each release is also signed with my PGP key, which confirms that I published that specific file to GitHub, independent of GitHub's account security. This is in addition to the `apksigner` check, not a replacement for it.

**Public key fingerprint:**
```
9EA2 8F46 7802 5092 7643 1B69 42B5 FA42 AA63 90E1
```

Import the key from my website and confirm that the fingerprint GPG prints matches the one above. That match is what establishes trust:

```
curl -O https://mowtiie.cc/PGP_PUBLIC_KEY.asc
gpg --import PGP_PUBLIC_KEY.asc
```

Each release includes a detached `.asc` signature alongside the APK. Verify a downloaded release with:

```
gpg --verify supanote-vX.X.apk.asc supanote-vX.X.apk
```

A valid signature looks like:

```
Good signature from "Mowtiie <mowtiie.dev@gmail.com>"
```

## Building from Source

Requires Android Studio (or the Android SDK) and a JDK. Debug builds work out of the box:

```
./gradlew assembleDebug
./gradlew installDebug
```

`local.properties` can optionally hold `SUPABASE_URL` and `SUPABASE_KEY`. They pre-fill the setup screen in **debug builds only**, so you don't have to retype your test project's credentials on every install. Release builds always ship with these values empty.

Release builds are signed with a keystore described in a git-ignored `keystore.properties` file at the repo root:

```
keyAlias=...
keyPassword=...
storeFile=/path/to/keystore.jks
storePassword=...
```

Without that file, `assembleRelease` produces an unsigned APK.

## Contributing

Issues and pull requests are welcome. If you're filing a bug, please include your Android version and the steps to reproduce. Before opening a pull request, make sure `./gradlew assembleDebug lintDebug testDebugUnitTest` passes. Tests for the Keystore storage and the backup format run on a device or emulator with `./gradlew connectedDebugAndroidTest`. For release steps, see [RELEASING.md](RELEASING.md).

## License

This project is licensed under the GNU General Public License v3.0. See the
[LICENSE](LICENSE) file for details.
