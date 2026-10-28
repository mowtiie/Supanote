# Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Make sure `keystore.properties` exists at the repo root (see the README).
3. Build the signed release: `./gradlew assembleRelease`.
4. Verify it with `apksigner verify --print-certs --verbose app/build/outputs/apk/release/app-release.apk` and check the fingerprints listed in the README.
5. Optionally create a detached PGP signature: `gpg --armor --detach-sign supanote-vX.X.apk`.
6. Publish the APK, the optional `.asc` file, and `mapping-<version>.txt` on GitHub.

## Mapping files

Release builds are minified with R8. Each release should include the file at `app/build/outputs/mapping/release/mapping.txt`, renamed to `mapping-<version>.txt`. It is needed to deobfuscate stack traces from crash reports: match the file to the version shown under "App Info" in the crash report and use it with `retrace` from the Android SDK command-line tools.
