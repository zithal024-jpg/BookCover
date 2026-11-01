# BookCover private tester handoff draft

**Draft only. No candidate acceptance, installation or distribution is authorized by this document.** Combined reader, rewards and Cover Edges source `f32a762c6a7fae1e8b28322c1a9514121e7e83d1` passed build/lint, 431 unit checks and one 114-case supported emulator run. Three Fold-only checks and physical acceptance remain pending. This is a candidate source identity, not an accepted APK. Complete the verification rows and the pending gates before inviting a tester to install.

For private testers, this handoff supersedes historical APK/install/evaluation examples in [BUILD-AND-INSTALL.md](BUILD-AND-INSTALL.md) and [RESULTS.md](RESULTS.md). [CLAUDE.md](../../CLAUDE.md) and the [owner-phone install policy](ACTIVE-BACKLOG.md#owner-phone-installs) still govern the owner's phone. The [active backlog](ACTIVE-BACKLOG.md) remains the only live work queue.

## Sender before sharing

This candidate preserves the existing owner-update package `com.bookcover.diagnostic` and launcher name BookCover. The recorded future tester application ID `com.frankrodriguez.bookcover` is a separate track, not this candidate's package; adopting it creates another installation and does not migrate the existing library. Complete the remaining fields from the exact artifact and matching evidence, never an older build. SHA-256 is a file fingerprint; the signing certificate identifies the signing key. Stop on an unexpected package, launcher name or signing identity.

| Required field | Fill before use |
| --- | --- |
| Candidate source commit; acceptance is separate | `f32a762c6a7fae1e8b28322c1a9514121e7e83d1` — engineering QA passed; physical acceptance pending |
| Matching build source snapshot | `e93c02b12b69ab53b3c7e73256af749fa64a56378ee00ffdfb2a2393c0120a71` |
| Exact APK filename and sender's absolute file path | `C:\Users\Frank\Documents\Codex\2026-10-28\task\BookCover-0.4.0-prototype-f32a762.apk` |
| APK SHA-256 | `62b102bee26c2d64a89143e0951ee1086ec06128c2a2b7a7d258ba1750be4469` (71,383,202 bytes) |
| Display name | BookCover |
| Application ID of this candidate | com.bookcover.diagnostic |
| Recorded future tester application ID | com.frankrodriguez.bookcover — separate track, not this APK |
| Version name and numeric version code | `0.4.0-prototype` / `6` |
| Signer certificate SHA-256 and signing-track owner | Existing development/debug signer: `c4014c59942495ab82332093df8eae893655182ac31e7fe5bba84293205f3929`. Public-release signing decision/ownership remains PENDING. |
| Acceptance owner/date and preserved verification record | Owner/distribution acceptance PENDING. Codex engineering checks on 2026-11-01: 431 units and one 114-case emulator run, zero failures/skips; see the consolidation row in the active backlog and the supplied TESTS-MANIFEST.json. |
| Approved devices/Android versions and known limitations | PENDING |
| Approved delivery route and private feedback destination | PENDING |

1. Obtain the current designated integration owner's acceptance of the combined build and the owner's distribution approval. Confirm the signing/package update track and a data-preserving upgrade rehearsal. For the owner's phone, also meet the linked ancestry/integration/availability policy; this guide grants no exception.
2. Inspect the exact APK. These PowerShell examples are templates: replace every `REPLACE` path with an existing file/tool. Use the installed Android SDK build-tools version, not a guessed version. Record the SHA-256, signer and version outputs in the open rows above. This candidate's package line must be `com.bookcover.diagnostic`. Stop on any tool failure or mismatch.

   ```powershell
   $testerApk = 'C:\REPLACE\accepted-bookcover.apk'
   Get-FileHash -LiteralPath $testerApk -Algorithm SHA256
   & 'C:\REPLACE\Android-SDK\build-tools\REPLACE_VERSION\apksigner.bat' verify --print-certs $testerApk
   & 'C:\REPLACE\Android-SDK\build-tools\REPLACE_VERSION\aapt2.exe' dump badging $testerApk | Select-String '^package:'
   ```

3. Supply this completed handoff, the accepted ordinary-app APK and the nonpersonal `lantern-keeper.epub` and `river-maps.epub` from `app/src/main/assets/samples/` at the same source. Exclude QA/test APKs, signing keys, personal EPUBs and private diagnostics. Manual installation needs no repository or build tools.

## Recipient setup and safe updates

1. Wait for the completed identity record and explicit invitation. Use your own test device and generated/nonpersonal books. Android 11/API 30 is the declared minimum; that is not evidence that every Android 11+ device was tested. Outside-cover support additionally requires available rear-display capability.
2. If updating, first follow the backup precautions below and note your current books, passage, bookmarks, collection, settings and Streak. Keep the existing app installed. A matching display name or About label does not identify an APK; the current About text is hardcoded.
3. Compare the delivered APK's SHA-256 with the sender's record. On Windows, use `Get-FileHash -LiteralPath 'C:\REPLACE\accepted-bookcover.apk' -Algorithm SHA256`; otherwise ask the sender for help verifying it.
4. Open the supplied APK in Android Files/My Files and follow the installer. For an existing installation of the same application ID with a compatible signer and version, use **Update**. A sender-approved ADB alternative, with USB debugging authorized by the device owner, is:

   ```powershell
   & 'C:\REPLACE\Android-SDK\platform-tools\adb.exe' -s 'REPLACE_DEVICE_SERIAL' install -r 'C:\REPLACE\accepted-bookcover.apk'
   ```

   `-s` selects one device; `-r` requests replacement while retaining app data. A new package is a separate installation, not an automatic library migration. Open BookCover from its launcher afterward and compare the data noted before updating.
5. **If installation fails, stop.** Report the exact error, especially a signature conflict (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) or version downgrade (`INSTALL_FAILED_VERSION_DOWNGRADE`). Request a compatible accepted APK from the sender. Do not uninstall, clear storage/data or force a downgrade. A local rebuild may use a different key. Keep the working installation and original files.

These are manual checks. Do not run connected/instrumented tests against the ordinary app: cleanup can uninstall its target. Engineering device tests belong only to the separate `.qa` app through `scripts/Test-Device.ps1`, under the repository safeguards.

## What backup can recover

In Library, open **Settings → Backup → Save backup**. Choose a new filename outside the app's private storage; retain another known-good copy. Confirm a successful result. A provider write failure after opening the destination can leave a partial file, so do not overwrite your only backup.

Restore matches books by the SHA-256 of their original EPUB or PDF bytes. It **replaces matching books' collections and merges bookmarks**. It replaces a reading position **only when the backup's last-read timestamp is newer**. Missing books are reported; unrelated local books remain. This does not replace the whole library or rewind newer positions.

New backups also include dated reading history and completed goals, earned relic progress, and saved default/per-book relic appearances. History merges using the greater reading time per date instead of adding duplicate minutes; existing completed goals and earned rewards are retained. Explicit local appearance choices take precedence over restored choices. History and relic data can restore even when the books are absent. Older backups lacking these fields restore book metadata only; they cannot recover history or rewards they never contained.

**It is not a full backup.** Keep these independently:

- The identical original EPUB and PDF files. BookCover keeps private copies and has no book-file export in this workflow; changing the original bytes changes the book's identity.
- Original custom-cover image files; choose them again after recovery. Embedded artwork, when available, can be extracted again on import.
- A private note of general reader/Library/outside-cover preferences, which must be set again. Saved relic appearances are the exception included above.

Temporary testing access and test appearances are not backed up; earned settings return when testing ends.

Android cloud backup and device transfer are disabled by the app's configuration. If recovering into an empty installation, import the identical originals, then **Restore backup before opening the books** and check the matched/missing result; opening marks a book read more recently. Book/cover files and general preferences still need independent recovery. Malformed backup payloads are checked before changes, but a disk failure during restore can leave some files merged and others pending; keep the backup and report the error rather than assuming an all-or-nothing restore. A damaged/unreadable library or relic collection can refuse changes to protect data: report it rather than reset the app. Keep backups private: they can contain reading dates and personal relic inscriptions as well as book metadata.

## Reading walkthrough in 20 to 30 minutes

Record pass/fail/blocked for each step; a blocked step is not a pass. Use only the supplied generated samples, leaving personal books untouched.

1. **Import and Library, 4 minutes.** Copy the supplied sample EPUBs somewhere accessible in Files. First launch normally adds both samples. To exercise a fresh import, use an expendable sample entry only: **Options → Remove local copy** deletes that sample's local progress/bookmarks/cover, so do this only if none matters. Then **Import EPUB** and select its original sample file. Re-import it once more: expect a duplicate result, not another tile. Search its title/author, try the sort choices, and use **Move to collection** with a test name such as `Tester`. If neither sample is expendable, skip removal and mark fresh import untested.
2. **Read and reopen, 6 minutes.** Open the sample and swipe several pages. Note a recognizable sentence well beyond the first page. With controls/sheets closed, use Android Back until Library appears; reopen through **Continue reading/Resume**. Compare the actual sentence, not only the percentage. Repeat twice, then go Home and return. Record any jump to the start, delay or blank page; do not erase a failure by retrying until it passes.
3. **Bookmarks and search, 5 minutes.** Tap the page centre or **Controls**. Save a named **Bookmark**. Move elsewhere, then use **Contents → Bookmarks** to return. Use reader **Search** for words visible in the sample, select a result and check the passage. Open search again and **Close** without selecting: the page should stay put.
4. **Settings, 5 minutes.** Under **Aa**, change text size and Paper/Sepia/Night, noting passage retention. Under **More**, optionally enable edge taps or volume-key turns; close the sheet with **Done**, then tap **Read** before trying them. Restore your choices. In Library **Settings → Appearance**, change theme and check it remains selected when settings reopen. Report unreachable/clipped controls. Fixed-layout EPUBs cannot reflow text; the supplied samples are reflowable.
5. **Save and recheck, 3 to 5 minutes.** Save metadata to a new backup file. Restore that file and check the result, collection and bookmark remain usable. This same-install check does not prove full disaster recovery or an Android upgrade. Close and reopen BookCover normally and recheck settings/passage; do not force-stop, clear or reinstall it for this walkthrough.

## Separate physical Fold and outside-cover checks

Only after the sender approves this hardware check, with the device owner available. Keep BookCover foreground, use generated art, and record what a person actually sees on both panels. API status or emulator output is insufficient.

1. In Library **Settings → Outside cover**, note the original choices, enable **Start outside cover when opening a book**, then open a sample unfolded and observe automatic start. Stop it, disable automatic start in Library settings, and reopen. Use **Controls → Cover off → Start outer cover** for manual start, accepting any system consent prompt. Physically observe the cover while turning pages and changing text size.
2. While the manually started cover is running, fold/unfold and check passage/bookmark continuity and cover resumption. Use **Stop outer cover** and verify it disappears. Start it again, then exit to Library and check it stops. Report unavailable capability/status honestly.
3. The cover timer offers **30 min, 60 min, Manual**. For a separate expiry check, choose 30 minutes, then open/start a fresh cover session and record its start. Observe expiry and physical cover behavior; do not shorten the timer through developer tools. This lies outside the short walkthrough.
4. Under reader **More → Screen while reading**, choose 2 minutes. Note Android's own screen timeout and observe inactivity first with the cover off, then on. The app releases its reading wake flag after the chosen idle interval; Android's timeout follows, and the outer cover also has a wake flag. Measure actual sleep; do not promise sleep exactly at two minutes. Check Stop and lock/unlock behavior. Restore changed settings. Battery savings and a 60-minute battery baseline remain separate measurements.

## Private feedback

Send to the completed private feedback destination. Include APK filename/SHA-256, accepted commit/snapshot, package/version and signing identity from the handoff; device model, Android version, navigation mode, folded/unfolded state, relevant settings; numbered steps, expected/actual outcome, frequency (`2 of 3 attempts`), and time with timezone. Say which steps were blocked.

WebView provider/version may be obtained by an already-authorized ADB user without changing it:

```powershell
& 'C:\REPLACE\Android-SDK\platform-tools\adb.exe' -s 'REPLACE_DEVICE_SERIAL' shell dumpsys webviewupdate | Select-String 'Current WebView package'
```

Otherwise report `unknown`; Chrome's version is not a substitute. Optional diagnostics: **Library Settings → About → tap the version line seven times → Device diagnostics** displays a report, without automatic export/upload or WebView identity. Before sharing screenshots/log excerpts, remove private book text/covers/titles, file paths, notifications, accounts, serials and other personal data. Use generated samples; never send EPUBs or full backups as routine feedback. URI scrubbing does not guarantee personal data is absent.

## Known limits and gates before use

Local DRM-free EPUB and PDF reading is supported. PDF original pages retain their layout; optional Text view requires Android 15 or newer and selectable text, and may omit pictures/formatting or repeat lines. DRM, cloud sync and online catalogues are outside scope. The app has no Internet permission and rejects remote book resources; sending feedback is a separate human action. Partial backup coverage and device-dependent outside presentation remain limitations.

The combined `f32a762` candidate passed the guarded build/lint and 431 unit checks, plus **114/114 selected Android checks in one instrumentation process**, with no failures or skips. R1 lifetime, visible/saved search passage, later navigation, Library, PDF, backup/history and relic flows were included. Original failed attempts remain preserved; partitioned passes were not combined into this result. The original 117-case selection is not claimed passed: three Fold-state checks were blocked on this generic emulator. Physical Fold/reopen, outside-cover Start/Stop/timer/sleep and whole-app owner observations remain pending for this exact APK. Recheck the [active backlog](ACTIVE-BACKLOG.md#active-queue) before inviting testers.

Still required: the current integration owner's combined-candidate acceptance with preserved matching artifacts and resolved/dispositioned findings; verified signing/package/version and demonstrated upgrade preservation; owner-observed final cover/Stop/timer/sleep behavior; current accessibility/large-text and supported-device limitations; and owner approval of delivery/feedback routes. Historical passes do not satisfy these gates.

Public-release signing and third-party notice review remain separate gates. The
current source still has no root `NOTICE`, despite historical documentation claims;
the separate dependency audit must resolve that inconsistency. `LICENSE`, bundled
licence assets and the in-app licence viewer do exist. This handoff does not
establish legal compliance or public-release approval.
