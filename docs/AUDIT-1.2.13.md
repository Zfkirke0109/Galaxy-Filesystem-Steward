# Galaxy Steward 1.2.13: audit, fixes and verification

Baseline: commit `2cc31a9` (PR #3 branch), the code of 1.2.12 (12) installed on the S26 Ultra (SM-S948U, Android 17,
One UI 9). `main` was older, PR #4 only bumps pre-commit hooks, and there were no releases. Evidence: the device export
of 10-30 11:17 (logcat with the app's own history from 10-27 on, and the storage report of 11:17:15).

Status words: **FIXED** (changed, with a test that failed before and passes after), **FIXED (inspection)** (changed,
no automated test could reproduce the trigger here), **DOCUMENTED** (left as is, with the reason), **NEEDS DEVICE**
(only a phone can confirm it).

## Evidence from the device log

| # | When / where | Evidence | Root cause | Severity | Status | Regression test |
|---|---|---|---|---|---|---|
| E1 | 10-27 17:36:33, App leftovers screen | "helper connected in 535 ms" at 17:36:34, then "did not connect after 20001 ms" at 17:36:53 | The screen starts the app-folder scan and the deep-space count together. Both bind the Shizuku helper, and in Shizuku 13.1.5 `unbindUserService(args, old, false)` clears every connection for the service, so the second bind dropped the first caller's connection | High | FIXED: one bind at a time (Mutex), later callers share it | `ShizukuBridgeTest.callersAtOnceShareOneBind` (red: 2 binds) |
| E2 | Same event | Deep space showed nothing and logged no error | `withTimeout` throws TimeoutCancellationException, a CancellationException, which every controller rethrows as "the user left" | Medium | FIXED: the timeout is an IOException the screen shows, and the stuck helper is stopped so the next try starts fresh | `aHelperThatNeverAnswersFailsWithAnError` (red: TimeoutCancellationException) |
| E3 | 10 starts, 10-27 to 10-30 | "Shizuku is not ready for this app yet: Not an attached client" | Shizuku sets its binder before it attaches the app as a client; asked in between, it refuses. It passes by itself | Low | FIXED: status CONNECTING ("Check again") instead of "not running", growing retries for about 30 s, one info line, a warning only if it never attaches, and `helper()` waits up to 5 s | `notReadyAtStartIsRetriedInsteadOfCrashing`, `theHelperWaitsWhileShizukuAttachesTheApp` |
| E4 | Storage report 11:17:15 | "scan done … duplicate folders 0, clutter 3, to organize 0", while the scan at 11:16:12 found 1, 8 and 5 | The report printed the counts left after the Autopilot run under the scan's own line | Medium (misleading) | FIXED: the scan's line is kept, followed by "Left after N applied since: …" | `RunAccountingTest.theReportAfterARunSaysWhatTheScanFoundAndWhatIsLeft` |
| E5 | Autopilot 11:16:54 | "moved 4, deduplicated 6, quarantined 2 (68.8 MiB), removed 4 empty folders, freed 2.9 MiB" for 11 items / 16 operations | Reconciled with the scan: 6 = 5 files of the duplicate folder + 1 organize move that found its file already filed; freed 2.9 = 2.8 + 0.1 MiB; 68.8 MiB is still on disk until the quarantine is emptied. Correct counts, unclear wording | Low | FIXED: "already filed" is counted and said; folder moves count their bytes | `aMoveThatFindsItsFileAlreadyFiledIsCountedAsSuch` |
| E6 | Throughout | perf_hint, AdrenoVK and missed-GC lines | Logged for many uids, not this app | n/a | DOCUMENTED: platform-wide | none |
| E7 | CI run of 2cc31a9 | `RELEASE_SIGNED=false`; the 1.2.12 key lived in a previous build container | The release key isn't in GitHub secrets, and the old debug key is gone | High (updates) | DOCUMENTED: see Signing below | none |

## Findings from the code review (six reviewers, then a second review of the diff)

| Area | Finding | Severity | Status | Test |
|---|---|---|---|---|
| Storage | A run could quarantine or pack the copy it had just kept for a duplicate it deleted (a near-copy folder, or an old installer), leaving no copy outside the quarantine | High (data loss) | FIXED | `KeptCopyTest` (red: the photo was quarantined) |
| Storage | Undo marked a run undone even when items stayed in the quarantine ("Original path is occupied"), so they went at the next purge | Medium-high | FIXED: restored beside what has the name, and the run stays undoable while anything is left | `UndoLeftoversTest` (red ×2) |
| Storage | A failed journal write didn't stop the run | Medium | FIXED (inspection): the run stops, and the move just made is put back | none |
| Storage | Emptying the quarantine stopped at the first undeletable file, said it freed everything, and marked the run purged | Low-medium | FIXED (inspection) | none |
| Storage | The goal run counted packed folders as freed although their zips stay | Low-medium | FIXED | none |
| Storage | Folder quarantines and packs don't walk the folder again at run time; leftover app folders aren't re-checked for a reinstalled app | Medium | DOCUMENTED (plausible trigger) | none |
| Protected data | "Clear all data" was offered for Facebook (`com.facebook.katana`, `lite` and its helpers), which signs you out | Medium | FIXED | `AppStoragePolicyTest` (red) |
| Protected data | Audible's and Amazon Music's licence-bound copies made the user's own copy of a book look redundant | Low-medium | FIXED | `LayoutCleanupTest` (red) |
| Termux | A dated backup in the home folder holding a Git repository below its top level was offered as an old snapshot and deleted with `rm -rf` | Medium (data loss) | FIXED: any nested repository or project marker keeps it | `TermuxInventoryTest` (red) |
| Termux | Stop (or a timeout) stopped only the waiting; Termux finished the move, and nothing was journaled to move it back | High | FIXED for Stop: runs done by Termux or the Shizuku helper offer none. A timeout or process death mid-move still leaves no History entry: DOCUMENTED | none |
| Termux | Result PendingIntents were reused across process restarts, so an old answer could complete a new call | Medium | FIXED: a per-process nonce in the data URI | none |
| Termux | The results file in shared storage could be rewritten by another app with all-files access | Medium | PARTLY FIXED: always deleted after the call. Integrity check (hash on stdout) DOCUMENTED | none |
| Privacy | Apps opened in Secure Folder were named in exports by window lines (`input_focus`) and by user-first events not on the list. The 10-30 export itself still held 5 Secure Folder lines (`am_schedule_service_restart: [150,…]`) | High | FIXED: user-first event tags from Android's event-log tag list (each one user-first in the 10-30 log), and apps seen opening for another user are hidden in other lines. Run over the whole 10-30 export (258,563 lines, 2.45 s), the new filter removes exactly those 5 lines and nothing else | `OtherProfilesTest.appsOpenedInSecureFolderAreNotNamedByTheirWindows` (red) |
| Privacy | Exception messages put file paths into the log and exports | Low | FIXED | `StewardLogTest` |
| Privacy | A whole-device log export is written to shared Documents, readable by apps with all-files access | Medium | DOCUMENTED: changing where exports go changes the user's workflow; recommendation below | none |
| Helper | Deleting inside an app's cache follows a folder swapped for a symlink between check and use (helper runs as shell) | High if reachable | NEEDS DEVICE: whether an app can make such a link in its own Android/data on One UI 9. Hardening recommended | none |
| Concurrency | Hashes worked out before a scan was stopped were thrown away (the weekly audit could restart from zero every time) | Medium | FIXED (inspection): saved in `finally` | none |
| Concurrency | One unreadable subfolder (UncheckedIOException, DirectoryIteratorException) aborted a whole run | Low | FIXED (inspection) | none |
| Concurrency | Weekly upkeep isn't seen by the app's busy flag; stale loads overwrite pruned lists; settings changed mid-scan | Low-medium | DOCUMENTED | none |
| UI | Duplicates moved to the quarantine were reported as "removed" and not counted with quarantined items | Medium (setting off by default) | FIXED | none |
| UI | The Optimize confirm didn't mention packing folders (originals go to the quarantine) | Medium | FIXED | none |
| UI | A refused notification permission was ignored for the weekly audit; the upkeep notice called quarantined bytes "cleared" | Medium | FIXED: a notice, and honest wording | none |
| UI | Review screens keep showing work already done after a failed or stopped run; TalkBack labels on selection rows | Medium / low | DOCUMENTED | none |
| Reinstall | After a reinstall, quarantine runs have no journal: History couldn't undo them | Medium | FIXED: History offers "Put back" for them | `OrphanQuarantineTest` |
| Platform | The keep-alive notification can outlive its service after Android's daily data-sync budget | Low-medium | DOCUMENTED | none |
| Release | `testReleaseUnitTest` fails (Compose test manifest is debug-only); CI runs debug tests only | Medium (CI) | DOCUMENTED | none |
| Release | CI actions are pinned by tag, not by commit SHA, while the release key is present | Medium (supply chain) | DOCUMENTED | none |
| Release | verify-apk.sh didn't check 16 KB alignment or that the Termux script survives shrinking | Low-medium | FIXED | run on the 1.2.13 APK |

## Verification

All commands ran in a fresh build container. JDK 21; Gradle 8.14.3; AGP 8.13.2; SDK platform 36 and build-tools 36.0.0.
Maven Central rate-limited the container (HTTP 429), so a local-only Gradle init script used Google's mirror of Central.
The repository is unchanged by that.

- `./gradlew :core:test :app:testDebugUnitTest`: BUILD SUCCESSFUL, 147 tests, 0 failures, 0 skipped. That is core 137 and app 10; the baseline had 136. Tests marked "red" above were run against the old code first and failed there. The others cover behavior the old code didn't have at all: new counters, report lines and put-back.
- `./gradlew :app:lintDebug`: BUILD SUCCESSFUL.
- `shellcheck -S warning`:
  - `scripts/ci/verify-apk.sh` and `scripts/setup-signing.sh`: clean.
  - `termux-steward.sh`: the same 3 findings as on the baseline, none new.
- `GALAXY_STEWARD_VERSION_CODE=13 ./gradlew :app:assembleRelease`: BUILD SUCCESSFUL (R8 and resource shrinking).
- `scripts/ci/verify-apk.sh`: exit 0. Then `apksigner verify`, `aapt2 dump badging`, `zipalign -c -P 16 -v 4`, `readelf -l` and `dexdump` on the APK:

| Check | Result |
|---|---|
| Package | `com.galaxy.steward`, versionName 1.2.13, versionCode 13 |
| SDK levels | minSdk 26, targetSdk 36, compileSdk 36 |
| Native code | arm64-v8a, armeabi-v7a, x86, x86_64. The only native library, AndroidX graphics-path, has 16 KB LOAD alignment (0x4000), and zipalign `-P 16` passes |
| Kept by R8 | Shizuku helper service with `()` and `(Context)` constructors, AIDL interface, Termux result receiver, audit worker; the Termux script is in the APK |
| Signature | v2, one signer: the Android debug certificate of this build container. That certificate isn't stored, so the next build will have a different one |

Not done: no install or run on a physical device. Everything marked NEEDS DEVICE, and Shizuku's attach timing on One UI 9 in particular, needs a check on the phone.

## Signing and updating 1.2.12

1.2.12 was signed with a debug key from an earlier build container, which no longer exists, and the repository has
no release key in its GitHub secrets. No new build can update 1.2.12 in place: Android refuses an update signed by a
different certificate ("App not installed as package conflicts with an existing package").

Installing 1.2.13 means uninstalling 1.2.12 first.

What uninstalling deletes (app storage):
- History, with the journals that Undo needs;
- settings and your own rules;
- choices the app learned;
- the hash cache;
- scan memory.

What stays (shared storage):
- every one of your files;
- the quarantine (`.StorageSteward/Quarantine`). 1.2.13's History offers **Put back** for quarantine runs it has no journal of, and never empties them on its own.

Before uninstalling, it is worth using 1.2.12's History:
- undo any run you still want undone;
- empty the quarantine of any run you are sure about.

To end this for good, set up a release key once with `scripts/setup-signing.sh` (see `docs/SIGNING.md`). CI then signs
every build with the same key, so each one updates the last in place.

## Recommendations not done here

- Write a pending journal entry before a Termux move or clean, so a timeout or process death leaves something to finish
  or move back.
- Check the Termux results file against a hash the script prints on stdout.
- Keep whole-device log exports in app storage and share them through the share sheet, copying to Documents only on
  request.
- In the shell helper, open folders relative to their checked parent without following links (`openat` + `O_NOFOLLOW`,
  or `SecureDirectoryStream`).
- Pin CI actions to commit SHAs; add `distributionSha256Sum` to the Gradle wrapper; run release unit tests.
- Mark the app busy during weekly upkeep; prune and untick from a stopped run's journal; label selection rows for TalkBack.
