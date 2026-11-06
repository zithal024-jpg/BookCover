# Releasing BookCover to other people

This explains the **shared release** of BookCover: the signed app file other people
download. It is separate from the owner's own phone copy.

| | Owner's phone copy | Shared release | Device-test app |
| --- | --- | --- | --- |
| Application ID | `com.bookcover.diagnostic` | `com.frankrodriguez.bookcover.reader` | `com.bookcover.diagnostic.qa` |
| Built by | `scripts\Build.ps1` (debug) | `scripts\Build-Release.ps1` (release) | `scripts\Test-Device.ps1` |
| Signed with | development (debug) key | release key below | development (debug) key |

Because the IDs differ, installing the shared release never replaces or reads the owner's
library, and updating the owner's copy never touches the shared release. A library does not
move between them automatically; use **Settings → Backup** if that is ever wanted.

## The release signing key

Android only accepts an update if it is signed with the same key as the installed app.
**If the release key is lost, nobody can receive updates**: they would have to uninstall
(losing their library) and install a new app.

- Location: `C:\Users\Frank\BookCover-Signing\` (outside OneDrive and Git, on purpose).
  - `bookcover-release.jks`: the key itself (PKCS12, RSA 4096, alias `bookcover`, valid to 2126).
  - `signing.properties`: where the key is and its password. Keep private.
- Certificate SHA-256 (public, safe to share; lets people check a download is genuine):
  `8c166f2695498803fb23ab5483f2bb622078a8c040f1fbd664965f3ecf1393c6`
- **Back up the whole folder** to at least one offline place (for example a USB stick kept
  somewhere safe). Never commit it, upload it, put it in OneDrive or send it to anyone.
- The build looks for the key at that path by default. Another location can be given with
  `-SigningProperties` (script), `-Pbookcover.signingProperties=...` (Gradle) or the
  `BOOKCOVER_SIGNING_PROPERTIES` environment variable. Without a key, `assembleRelease`
  still builds, but only an unsigned APK, which `Build-Release.ps1` rejects.

## Building a release

1. Make sure the normal checks pass on the exact source:
   `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId release -Owner NAME`
2. Build and check the signed APK:
   `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build-Release.ps1 -Owner NAME`
   It holds the same BUILD reservation as `Build.ps1`, runs `assembleRelease lintRelease`,
   and refuses the result unless the APK has the shared package name, is not debuggable,
   requests no INTERNET permission and is not debug-signed.
3. Output (git-ignored): `output\release\BookCover-VERSION.apk`, its `.sha256` file and an
   `.identity.json` record (size, SHA-256, package, version, signer, permissions, commit,
   and whether build inputs had uncommitted changes). Release only an APK built from a
   committed, clean source (`UncommittedBuildInputs: false`).

R8 shrinking is off for now: Readium and PDFium use reflection, and a shrunk build would
need its own device QA before it could be trusted.

## Publishing an update

1. Increase `versionCode` (by one) and `versionName` in `app/build.gradle.kts`. Android refuses
   an update whose `versionCode` is not higher than the installed one.
2. Build, check, and test as above. Device tests still use only the `.qa` app.
3. Attach the new APK and its `.sha256` file to a new release on the public download page,
   with short release notes. Keep earlier releases available.
4. Record the release (commit, APK SHA-256, signer) in `docs/review/ACTIVE-BACKLOG.md`.

Publishing anything public needs the owner's explicit yes each time.
