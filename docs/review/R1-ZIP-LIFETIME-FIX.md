# R1 — deterministic reduction and narrow lifetime fix

2026-10-29, Codex. **Local fix verified; integration and candidate acceptance remain with Opus. No owner-phone access or installation.**

## Source and ownership

- Branch/worktree: `codex/r1-zip-lifetime`, `C:\Users\Frank\.codex\worktrees\r1-zip-lifetime\Book Cover - App`.
- Base: documentation-only `474db75`, whose product/test inputs equal frozen candidate `7bd5ff84344d6ceff512ebb344118bb527732c82`.
- Fix/test commit: **`19b70921e8a9e5affb2bb2f9653ad1bead597e52`**.
- Fixed tested source snapshot: `2edb1267e8e57fe43168bf5118e1c1534ed1d57041f5ef0733961e16aecc4b3b`.
- Four changed source paths: `library/ReadiumEngine.kt`, new `library/PublicationResources.kt`, new JVM `library/PublicationResourcesTest.kt`, new Android `testing/PublicationLifetimeTest.kt` (under their existing source roots).

Read CLAUDE.md, active queue and the records at `474db75`, `cd639f4` and `f87ae94`; no local `.agents/skills` directory was present. Integration worktree was clean when checked. Existing h3 changes in EpubSession, EpubReaderScreen, ReadingComfortPreferences, NativeReaderTest and ReadingKeysAccessibilityTest were preserved. No session, F3, reader-test disposition, dependency or build-helper edit.

## Captured failure and reduced cause

Independently re-read the fresh `6b09295` raw corpus failure in `verify7b-previous-06d9dca39156e422/receipts/previous6b-corpus1`. The recreated embedded-font MainActivity was DESTROYED at 07:41:47.024 EDT; the first Chromium exception was .072, **48 ms later**. MathML had not opened an activity. The failed parameter does not identify the responsible book. The retained run remains **nine passes, one fatal, 18 unrun**. The handoff's candidate2 raw archive directory was absent at the stated native ToolRoot; its two reported crashes were not freshly re-inspected here.

The focused reduction now identifies its own resource, archive and closer:

1. Open a newly generated embedded-font EPUB with the real `ReadiumEngine`; obtain `EPUB/style.css` through the publication and call Readium `asInputStream()`, the adapter WebViewServer uses.
2. Resolve length with `available()` without reading entry bytes. Keep the generated EPUB on disk.
3. Call `publication.close()`; observe the actual underlying ZipFile becoming closed. The QA-only observer uses reflection into pinned Readium 3.4.0 and a timeout/yield loop, not a fixed delay or injected archive close.
4. Read 16 bytes from the previously handed-out stream. **Pre-fix, this fails with the exact captured lower stack:** `ZipFile.ensureOpen/getInputStream → FileZipContainer.Entry.stream:124 → readRange:104 → read:85`, `IllegalStateException: zip file closed`.

Pre-fix log: QA PID 6370, 14:48:51.725 EDT handed out the CSS stream and called publication close; .732 observed archive closed, then read. No fixture deletion preceded the failure. This establishes a repeatable publication/resource lifetime defect independent of corpus order, deletion or a particular WebView timing. It does **not** recover the historical request/archive/closing caller.

Readium closes its FileZipContainer asynchronously while Resource.asInputStream defers ranged reads; FileZipContainer catches IOException/ZipException but not this IllegalStateException. [Chromium 133 InputStreamUtil](https://raw.githubusercontent.com/chromium/chromium/133.0.6943.137/components/embedder_support/android/java/src/org/chromium/components/embedder_support/util/InputStreamUtil.java) handles IOException as a resource-request failure; IllegalStateException escapes. Cached 3.4.0 source review found no independent post-success asset closer in the configured local EPUB path: the parser adopts ContainerAsset's container into the publication; the opener and engine do not separately close it afterward. Ownership is by convention, and this source check is not historical runtime attribution.

## Change and validation

`PublicationResources` is installed before search captures the container and before markup wrapping. It marks publication resources closed immediately, refuses new lookups/operations, and returns Readium's typed access error for late retained-resource calls. It closes the underlying container once all already-admitted operations finish. Individual resource close also waits for that resource's active calls. No monitor spans suspension; cancellation/throws release counters in finally. Unexpected read exceptions still escape. Unconsumed streams do not hold the archive open; there is no timer, arbitrary sleep or broad exception catch.

| Check | Result |
|---|---|
| Pre-fix actual ZIP regression | **1/1 failed as expected**, exact closed-ZIP stack; snapshot `6f69afb3565028b500128cb909cbba79f92a0ff534991cfab28732b0a860e17d`; operation `c664fae8cf8d493baccb6c3413dbf5a7` |
| Fixed unchanged ZIP regression | **1/1 passed**; late access becomes IOException, archive is actually closed |
| Fixed stock EpubCorpusTest | **28/28 passed**, zero failures/errors/skips/unrun parameters |
| Build.ps1: assembleDebug / testQaUnitTest / lintDebug / lintQa | **Passed; 281/281 units**, zero errors/skips; lint zero errors, 26 debug / 30 QA warnings |

Eight new deterministic JVM tests cover retained streams, all three active resource operations, multiple resources, resource/container close ordering, cancellation, unexpected exceptions, cleanup failure and nested guards. They use deferred signals, not sleeps. Existing F3 `BookOpenLifecycleTest` remains **7/7 passed**. JVM tests use the same internal wrapping method as get because Android URL construction is unavailable on plain JVM; the actual QA regression checks real engine/container wiring.

All live operations used the supported helpers, explicit release evidence and only `com.bookcover.diagnostic.qa` on released **emulator-5584 / BookCoverInnerQA (Android 16)**. No main-app storage or personal books were used. One initial regression harness invocation had a non-void JUnit method signature; no reproduction ran in that invocation. It was corrected before the red test and is retained separately as `harness-signature-error`.

## Source-bound evidence and handoff

Native ToolRoot: `C:\Users\Frank\AppData\Local\BookCoverBuild`; task **`r1-fix-0cba3ecd6474e212`**.

- `receipts/red-lazy-stream`: failed receipt, actual test XML/logcat and independent matching source-after snapshot. Failed receipt stays Failed with null SourceAfterId; its archived build receipt is the earlier harness build, not a matching red-source build claim.
- `receipts/green-lifetime-corpus`: final build/device receipts, 29 passing case reports, combined manifest and independent source-after snapshot.
- Build operation `bd4eae3c69284a12bbb4150659a6af82`: 18:51:39–18:53:07 UTC. Device operation `86113a14b1894c2c959150f806408381`: 18:53:23–18:55:13 UTC. Both source-before/after IDs equal the fixed snapshot above.
- Combined manifest: `evidence/2edb1267e8e57fe43168bf5118e1c1534ed1d57041f5ef0733961e16aecc4b3b-0db1ef24b4674c6296cf95956152a5d5.json`, independently validated with Get-BookCoverPackageInputs in **Windows PowerShell 5.1**. A first PowerShell 7 check hit case-sensitive path spelling; report hashes/sizes matched, and supported PowerShell validation passed without source/report changes.
- Debug APK SHA-256: `70ccf8e4884d887bf5d9fc695fdbc34b4bcc596d8b6d8b5992b0ac4d447276ad`. Not installed on the owner's phone. All helper leases released; the borrowed emulator was not stopped.

The before/after deterministic regression supports this fix; the single green corpus run alone would not. No historical request was traced, no normal-app stress/physical-phone validation was added, and the nine shared reader-test failures were not rerun or waived. Opus owns those dispositions and integration. No push, merge, release, new frozen-candidate acceptance or owner-validation claim.
