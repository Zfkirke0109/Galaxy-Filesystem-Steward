# Galaxy Steward 1.2.14: implementation, review and verification

Baseline: commit `002eb24`, the code of 1.2.13 (13), with 139 engine and 10 app tests. Final code: commit `503497c`,
with 216 engine and 13 app tests. Two rounds of independent review were run: five reviewers on the baseline, then two
on the fixed code. The second round found no critical or high defect. Every confirmed finding below was fixed, and
wherever a test could reproduce the trigger, the fix comes with a test that failed before it and passes after.

Status words: **FIXED** (changed, with a test that failed before and passes after), **FIXED (inspection)** (changed;
no test here could reproduce the trigger), **DOCUMENTED** (left as is, with the reason), **NEEDS DEVICE** (only a
phone can confirm it).

## Systems

| System | Where | Evidence |
|---|---|---|
| Scanning | `scan/TreeScanner` (6 listing threads, links never followed, an unreadable folder marks only itself) | `TreeScannerTest`, `RescanTest`, `ScanMemoryTest` |
| Safety | `SafetyPolicy`, `PathGuard` (pins and the Android and steward folders ignore case), `AppPolicy` | `PolicyAndRulesTest`, `AppStoragePolicyTest` |
| Streaming hashing, duplicates | `dedupe/DuplicateFinder` (quick fingerprint, then full SHA-256 on 2-6 workers), `FolderAnalyzer` | `DedupeTest`, `NameMergeTest`, `OverlapMergeTest`, `NearCopyTest` |
| Classification, organization | `organize/Rules`, `FilingModel`, `OrganizePlanner` | `KeywordRuleTest`, `FilingModelTest`, `OrganizeJunkOptimizeTest` |
| Destinations, collisions | `PathGuard.uniqueCollisionPath`; an identical file already filed is a verified duplicate | `OrganizeJunkOptimizeTest`, `RunAccountingTest` |
| Junk | `junk/JunkPlanner` | `OrganizeJunkOptimizeTest`, `LayoutCleanupTest` |
| Overlap detection | `FolderAnalyzer.overlaps` (exclusive, deterministic) | `OverlapMergeTest` |
| Preview and dry run | `exec/Preflight`: every confirm dialog checks each step against storage first, changing nothing | `PreflightTest` |
| Approved apply | `exec/ActionExecutor`, `appdata/AppDataExecutor`; nothing changes without the confirm dialog | `AppFlowTest`, `KeptCopyTest` |
| Verification, journaling, recovery | Write-ahead journal (`PENDING:` and `ABANDONED:` lines, settled at undo); a fresh SHA-256 before every delete | `InterruptedRunTest`, `ExecutorJournalFailureTest`, `AppFolderJournalTest` |
| Undo and rollback | `exec/RollbackEngine`; nothing is overwritten; anything left keeps the run undoable | `UndoRoundTripTest`, `UndoLeftoversTest`, `AppFolderUndoTest` |
| Cancellation, progress, interruption | Cancellation points in every planner and step; throttled progress; interrupted runs offered for Put back | `InterruptedRunTest`, `QuarantinePurgeTest`, `ThrottleTest` |
| UI | Compose screens, one run at a time, confirm dialogs with the dry run | `AppFlowTest` (Robolectric, whole flow), `ClearDataDialogTest` |
| Settings, state, logging | `SettingsStore`, `JournalStore` (cached summaries), `StewardLog` (paths redacted) | `RunLogAndCacheTest`, `StewardLogTest` |
| Permissions | All-files access, the Termux permission, Shizuku | `PermissionHelpTest`, `ShizukuBridgeTest` |

## Issue A and issue B

**Issue A** (a placeholder loop in `overlaps()`): there never was one. `git log -L` shows `overlaps()` only in its first
commit, with a real pairwise algorithm. The real defect was that each pair was planned alone: a folder could be merged
away twice, or into a folder another merge emptied, and merges disagreed with duplicate groups and filing about which
copy stays. Merges are now exclusive and deterministic, duplicate groups keep the copy a merge keeps, and filing
leaves merged folders alone (`OverlapMergeTest`, six cases).

**Issue B** (`*` normalization): a trailing `*` was kept as a prefix. What went wrong was elsewhere: keywords in other
scripts normalized to nothing or to fragments (`Über*` became " ber"), so a rule fell back to matching every file of
its types; dotted İ split words; a newline in a keyword corrupted the saved rule. Now letters of every script are
words, Latin, Greek and Cyrillic accents fold away, marks that make another letter stay (ガス is not カス, ข้าว is not
ขาว, बिल is not बाल), scripts written without spaces match anywhere, and a keyword with no letter or digit makes the
rule match nothing (the editor refuses it). `KeywordRuleTest`, 15 cases.

## Round 1 (baseline `002eb24`, five reviewers)

| # | Finding | Severity | Status | Test |
|---|---|---|---|---|
| Core 1 | A second run from the same scan quarantined the only kept copy (a near-copy folder holding it) | High | FIXED | `KeptCopyTest` |
| Core 2 | Nested near-copy pairs put both copies in the quarantine | Medium-high | FIXED | `NearCopyTest` |
| Core 3 | With hidden files included, a Gallery-trashed copy could be the keeper | Medium | FIXED | `DedupeTest.aTrashedCopyIsNeverKept` |
| Core 4 | Folder-group ids collided (duplicate Compose keys) | Medium | FIXED | `DedupeTest` |
| Core 5 | Name merges moved `.nomedia` and hidden files | Low-medium | FIXED | `NameMergeTest` |
| Core 6 | Goal totals counted overlapping items twice | Low | FIXED | `GoalPlannerTest` |
| Core 7 | A newline in a keyword corrupted the rule | Low | FIXED | `KeywordRuleTest` |
| Safety 1 | Deep space could remove private data of a debuggable Termux | High | FIXED: the same refusals as Clear all data | `AppStoragePolicyTest`, `AppDataTest` |
| Safety 2 | Items could reach the quarantine without a journal line | Medium | FIXED: journal written ahead, verified moves journaled | `InterruptedRunTest`, `ExecutorJournalFailureTest` |
| Safety 3 | Undo of app-folder runs put back, as shell, whatever was in the quarantine | Medium | FIXED (round 2): digest and size checks | `AppFolderUndoTest` |
| Safety 4 | Undo after a reinstall made an invisible `com.app (restored)` | Medium | FIXED: it stays in the quarantine | `AppDataTest` |
| Safety 5 | Termux "Always keep" was ignored by age-only clean-ups | Medium | FIXED | `TermuxInventoryTest` (red on the old script), `TermuxManageTest` |
| Safety 6 | Pins matched case-sensitively | Medium | FIXED | `PolicyAndRulesTest.pinsAndTopFoldersIgnoreCase` |
| Safety 7 | Forged relocate results became journal entries | Low | FIXED | `TermuxManageTest` |
| Safety 8 | Undo of an unreadable journal marked the run undone | Low | FIXED | `QuarantinePurgeTest` |
| Safety 9 | `mv` without `-T` in the Termux script | Low | FIXED (inspection) | none |
| Android 1 | Archived apps (Android 15+) looked removed | Medium | FIXED | NEEDS DEVICE |
| Android 2 | Unticked goal picks came back after rotation | Medium-low | FIXED (inspection) | none |
| Android 3 | A release build without a version code got code 1 | Low-medium | FIXED: the build refuses | build output |
| Android 4 | Weekly audit stopped silently at the 10-minute limit | Low-medium | FIXED (inspection): idle constraint, stop reason | none |
| Android 5-8 | Emptying bypassed the one-run guard; permission buttons dead after a permanent denial; Back on app leftovers; labels on the main thread | Low | FIXED (5, 7, 8 by inspection; 6 tested in round 2) | `PermissionHelpTest` |
| Perf 1 | A rescan kept two full trees | High (for big trees) | DOCUMENTED: the old tree is dropped on low memory; the phone's own logs show about 190 files in shared storage | none |
| Perf 2 | Planning allocations | Medium | DOCUMENTED: same reason | none |
| Perf 3 | History refresh cost | Medium | FIXED: journal summaries cached, one refresh per run | none |
| Perf 4 | Learned filing was quadratic in homes | High | FIXED: 13.3 s to 0.36 s, same picks | `FilingModelTest` benchmark |
| Perf 5-14 | Signature formatting, audit loop, main-thread labels, pipe writers, unreadable listings, progress churn, no wake lock, Termux same-size files read whole | Low to medium | FIXED (inspection; 5 and 10 run under the existing folder-duplicate and Termux inventory tests) | `DedupeTest`, `TermuxInventoryTest` |
| Release 1 | The release key signed branch builds; every main push became the latest release | High | FIXED: key on main only, pre-releases | CI run |
| Release 2 | Quarantine purge untested, no containment check | Medium | FIXED | `QuarantinePurgeTest` |
| Release 3 | Debug builds replaced the app | Medium | FIXED: `.debug` suffix (round 2: own quarantine) | `AppStoragePolicyTest`, `QuarantinePurgeTest` |
| Release 4-9 | Journal failures untestable; undo accuracy unproven; skippable or hanging script tests; Robolectric downloads; upkeep untested; release identity | Medium to low | FIXED | `ExecutorJournalFailureTest`, `UndoRoundTripTest`, `UpkeepTest` |
| Release 10 | Supply chain | Low | PARTLY: wrapper SHA-256, repository content filters, opt-in mirror; no `verification-metadata.xml` | none |

## Round 2 (fixed code, two reviewers)

| # | Finding | Severity | Status | Test (red before) |
|---|---|---|---|---|
| Core 1 | A packed folder whose name was taken at once lost its zip | Medium | FIXED | `ExecutorJournalFailureTest.aPackedFolderWhoseNameIsTakenAtOnceKeepsItsZip` |
| Core 2 | A keeper you choose in a folder offered for the quarantine re-opened core 1 | Medium | FIXED: such clutter leaves the list | `KeptCopyTest.aCopyYouChoseToKeepTakesItsFolderOutOfTheClutterList` |
| Core 3 | The copy a merge keeps wasn't protected | Medium-low | FIXED | `OverlapMergeTest.theCopyAMergeKeepsIsKeptByItsGroupAndNotOfferedAsClutter` |
| Core 4 | Keyword normalization removed marks that change the letter | Low-medium | FIXED | `KeywordRuleTest.marksThatMakeAnotherLetterAreKept` |
| Core 5 | A failed move left its line open, readable as done | Low | FIXED: closing lines, and settling checks later lines | `InterruptedRunTest` (two cases) |
| Core 6 | Undo of app-folder runs ignored an unreadable journal and unrecorded items | Low | FIXED (inspection) | none |
| Core 7 | Emptying by age could delete through a linked folder | Low | FIXED | `QuarantinePurgeTest.emptyingByAgeNeverDeletesThroughALinkedFolder` |
| Core 8 | The Termux home is hard-coded | Low | DOCUMENTED: Termux compiles `/data/data/com.termux/files/home` in for every user, and the app's own command path assumes it too | none |
| App 1 | A debug build shared the app's quarantine and could clear its data | Medium | FIXED | `AppStoragePolicyTest`, `QuarantinePurgeTest.aDebugBuildNeverEmptiesTheAppsQuarantine` |
| App 2 | History listed a run only after the media rescan | Medium | FIXED | `AppFlowTest` (timed out before) |
| App 3 | The audit's new constraints never reached existing schedules | Medium-low | FIXED (inspection): rescheduled once per version | none |
| App 4 | Refreshing History could end the app | Low-medium | FIXED | `QuarantinePurgeTest.countingWhatNoJournalRecordsSurvivesFilesGoingMeanwhile` |
| App 5 | A run still going counted as interrupted | Low | FIXED | `QuarantinePurgeTest.aRunStillGoingIsNotTakenForAnInterruptedOne` |
| App 6 | Progress dropped the first step of a new phase | Low | FIXED | `ThrottleTest` |
| App 7 | A stopped audit skipped its rescan | Low | FIXED (inspection) | none |
| App 8 | App info opened after a first request closed with Back | Low | FIXED | `PermissionHelpTest` |
| App 9 | Put back removed empty folders an Undo still needed | Low | FIXED | `QuarantinePurgeTest.puttingBackLeavesARecordedFolderAsItWent` |

The second round also checked the round-1 fixes and found them correct, apart from the findings above.

## Android and the S26 Ultra

- **Target.** Built against API 36 (Android 16) with minSdk 26. The API 37 SDK is available, but targetSdk stays 36:
  adopting Android 17's behavior changes needs the phone to test them. On Android 17 the app runs with API 36 behavior.
- **16 KB pages.** The only native code is AndroidX's `libandroidx.graphics.path.so` (from Compose). Each ABI's LOAD
  segments are 16 KB aligned, the libraries are stored uncompressed, and `zipalign -c -P 16` passes.
- **Concurrency.** Hashing uses half the cores, 2 to 6 (4 on the Snapdragon 8 Elite Gen 5); listings use 6 threads,
  which keeps the storage daemon busy without starving the phone. No GPU, NPU or native work: hashing and listing are
  bound by storage, and no workload measured here would gain from them.
- **Background work.** The weekly audit runs only while charging, with battery not low and the phone idle, logs why
  Android stopped it (Android 12+), and holds a capped wake lock while a run lasts.
- **Archived apps** (Android 15+) count as installed, so their folders are never offered as leftovers.
- **Shizuku.** One bind at a time, and app-folder Undo checks what it puts back (see round 2).

## Performance

| Measure | Before | After |
|---|---|---|
| Learned filing, 1,500 homes and 800 loose files | 13,336 ms | 357 ms (same picks) |
| Termux identical-file check, same-size files | every file read whole | only files whose first and last 64 KiB match are read |
| Dry run before applying | none | a stat or two per step; no file is read |

## Build reproducibility

- The Gradle distribution is pinned by SHA-256, checked against the official checksum file.
- Google's repository serves only Google's groups.
- Robolectric's Android jar comes through Gradle, and the tests run Robolectric offline.
- A mirror of Maven Central is opt-in (`gradle/init/central-mirror.gradle.kts`, only with `GALAXY_STEWARD_CENTRAL_MIRROR` set, https only).
- The repository holds no keys, passwords or machine paths.
- CI gives the release key only to main's own builds, publishes them as pre-releases, and refuses a tag of another commit.

## Verification

Commands, each run from scratch (`--rerun-tasks`, no build cache):

```
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug --rerun-tasks
./gradlew -PgalaxySteward.versionCode=14 :app:assembleRelease
scripts/ci/verify-apk.sh app/build/outputs/apk/release/app-release.apk dist
```

Results at `503497c`: engine tests 216/216, app tests 13/13 (Robolectric), lint "No issues found", and shellcheck 0.11.0
(`-S warning`) clean on the Termux script and the build scripts.
APK: `com.galaxy.steward` 1.2.14 (14), minSdk 26, targetSdk 36, APK Signature Scheme v2, 1,975,294 bytes,
SHA-256 `497a7dabb05e1f606ffa8627d57856d6f01ab82b025c308feaa764e5f1538fd1`.

## Limitations

- **No device testing.** Every test here ran on the JVM or under Robolectric. Shizuku, Termux, the media index, One UI 9
  and Android 17 itself are NEEDS DEVICE.
- The dry run checks what costs no reading. A run still re-checks every step, with a fresh SHA-256 before each delete,
  and a folder going to the quarantine is not walked again at run time.
- App-folder Undo can't tell a quarantined file rewritten with its size kept and its date set back. Telling that apart
  would take reading every file twice.
- Perf 1 and 2 (memory at very large trees) were not needed for the phone's workload. Release 10 has no dependency
  verification metadata. Screenshots are recorded, not compared.

## Signing and updating 1.2.13

The installed 1.2.13 was signed with the build container's debug key (certificate SHA-256 `adcf4d47…7897`). 1.2.14 is
signed with the same key, so it installs over 1.2.13 as an update, keeping settings, History and permissions. A
release-key build from CI (docs/SIGNING.md) can't update it: switching keys once means an uninstall, which removes
History. The quarantine folder in shared storage survives that.
