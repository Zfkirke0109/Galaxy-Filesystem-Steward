# Galaxy Steward 1.2.15: what the 11-19 logs show, and more space freed

Baseline: 1.2.14 (14), code commit `503497c`, with 216 engine and 13 app tests. Final code: commit `abfba2a`, with
221 engine and 15 app tests; later commits change only tests and documents. Input: the 11-19 logcat export, a whole
device log read through Shizuku from 09:46:50 to 10:09:55. It came with the storage report saved at 10:09:51. Both are
from a samsung SM-S948U (m3q) on Android 17 (API 37), build CP2A.260605.016.S948USQS6BZK4.

Status words, as in the earlier audits:

- **FIXED**: a defect, changed, with a test that failed before the change (the failing run is recorded) and passes
  after it.
- **ADDED**: new behaviour, with tests. Nothing existed before for them to fail against, so only passing runs exist.
- **FIXED (inspection)**: changed, but no test here can reproduce the trigger.
- **DOCUMENTED**: left as is, with the reason.
- **NEEDS DEVICE**: only a phone can confirm it.

No phone was used for this work. Nothing was installed on one.

## What the log shows

| When | What | Source |
|---|---|---|
| 10:09:18 | The app starts (pid 23191, uid 10451). All-files access, usage access and Shizuku are all on. | ActivityManager, export header |
| 10:09:21 | Scan: 2.0 s, 2,144 files, 25.6 GiB. Found 16 duplicate files (1.3 GiB), 2 merges, 44 clutter items (979 MiB) and 53 items to organize. | `GalaxySteward` |
| 10:09:46 | Autopilot: 90 items, 115 operations, 2.3 s. It moved 59 (4.0 GiB), deduplicated 41, quarantined 11 (2.2 GiB) and removed 13 empty folders, then reported **freed 0 B**. Skipped 1: "the unpacked copy no longer matches the zip". | `GalaxySteward` |
| 10:09:53 | Shizuku helper connected in 299 ms. The logcat export was saved. | `GalaxySteward` |

The app's process shows no crash, ANR or kill: no `AndroidRuntime` error, no `ANR in com.galaxy.steward`, and no
`am_kill`, `am_crash` or `am_anr` for it. Its other error lines come from vendor and platform libraries that every app
loads: Adreno `QSPM AIDL service doesn't exist`, `perf_hint: Session ID too large`, `VibratorInfo: Invalid frequency
profile` and `libpenguin.so not found`.

**Shizuku `NoSuchElementException: Death link does not exist`.** This is DOCUMENTED, not changed. It shows up three
times in the log: in this app, in Galaxy Compressor (uid 10460) and in InstallerX Revived (uid 10490). Each time the
stack is inside the library (`rikka.shizuku.Shizuku.onBinderReceived` → `BinderProxy.unlinkToDeath`, Shizuku 13.1.5).
In this app, two binder threads logged `binder received` in the same millisecond (10:09:18.613). One delivery then
failed, because the library's handler isn't synchronized. The other one attached
(`sendBinder is called when already a living binder`), and the helper connected.

A lock around the provider's `call()` would remove the race. But the Shizuku server may call back while the lock is
held, and nothing here can show that it doesn't deadlock. So the change waits for a phone to test it on, or for a fix
in the library.

## Where space was left unused, and what changed

| # | Finding | Cause | Change | Status | Test |
|---|---|---|---|---|---|
| 1 | Autopilot "freed 0 B" while 2.2 GiB went to the quarantine | Duplicates go to the quarantine with *Quarantine duplicates* on (this phone). Only History could empty a run, and few look there. | The result dialog offers "Free X now" for that run. A second question says Undo can't bring it back, and that picked files and a recycle bin's content have no other copy. X is the quarantined figure the dialog already shows. | ADDED | `OutcomeDialogTest` (2) |
| 2 | Samsung My Files' recycle bin, 242 MiB in `Android/.Trash/com.sec.android.app.myfiles/<volume>`, was never offered | Everything under `Android/` is app-owned, both in `PathGuard` and in the clutter walk | What is inside a bin in `Android/.Trash/<app>` is offered like MT Manager's bin, one item at a time. It goes to the quarantine, and Undo puts it back in the bin. `Android/.Trash` itself, the app folders in it and the rest of `Android/` stay closed. Credential-like names stay closed too. | FIXED | `SystemTrashTest` (2) |
| 3 | An extracted zip (16.4 MiB) was offered at every scan and skipped at every run | The scan compared names and sizes only. A file had been edited in place since, at the same size. | The scan checks the CRC-32 of the unpacked files, up to 256 MiB per scan. A zip that doesn't match gets a "Left in place" note. Past that budget, the run's own check still catches it. | FIXED | `LayoutCleanupTest` |
| 4 | 121 MiB of logcat exports in `Documents/Galaxy Steward LogCat` | Exports were saved as plain text (36.7 MiB for the 11-19 one) | Exports are saved as a zip of one text file. The 11-19 log is 36.7 MiB of text and 5.2 MiB zipped (14%). The newest three are kept, and older plain-text exports count toward the three. The text size shown comes from the zip entry. | ADDED | `AppFlowTest` |
| 5 | The storage map listed the largest files, but none could be removed from there. On this phone those are 14.6 GiB in Movies, 4.3 GiB in DCIM/Camera and a 3.2 GiB zip in Download/Compressor. | The list was read-only | Files can be ticked on *Largest files*. *Move to quarantine* asks first, runs the dry run, and warns about camera originals. A new *Picked by you* category is never part of a goal or the weekly upkeep. What a run moved leaves the list. A file the run would leave alone (a pinned folder, a key file, app-owned storage) or a Git repository's own data shows "Protected: …" and has no box to tick. | ADDED | `PickedFilesTest` (3), `AppFlowTest` |
| 6 | "1 top-level folder belongs to installed apps: … Telegram (HushTelegram)" gave no size and no way to free it (466 MiB) | The note listed names only | The note lists sizes, largest first, and their total. It says the space is cleared inside the app, for Telegram at Settings → Data and Storage → Storage Usage. The steward still doesn't move or delete those folders. | ADDED | `LayoutCleanupTest` |
| 7 | The text size of a whole-device export counted characters, not bytes | Found in self-review: the copy loop counted characters | The size is read from the zip entry once it closes | FIXED (inspection): the test covers the app's-own-lines path; the whole-device path needs Shizuku | `AppFlowTest` (own-lines path) |
| 8 | CI run 34 failed `TermuxInventoryTest.auditFindsLeftoversCopiesAndDates`. It passed here and in CI up to 11-01. | `du -a` lists a file with several hard links once, under the name it meets first in the folder, and that order is the filesystem's. On ext4 here it met `model.bin`; on tmpfs, and on the CI runner since 11-19, it met `model-link.bin`. The test expected `model.bin`. | The test checks what it means: the copy is found, exactly one name of the linked file is in the group, and 1.5 MiB is extra. The script was right and is unchanged. | FIXED (test) | Failed on tmpfs before (`evidence15/13`); passes on tmpfs and ext4 (`14`). The whole suite also passes on tmpfs (`15`, `16`). |

Left alone on purpose:

- **Amazon Music**: 7.4 GiB in `Android/data/com.amazon.mp3` (11-10 app folder scan). It is protected, as are
  Facebook's login state and Audible. Only the app can remove its downloads.
- **Thumbnail caches**: 18.0 MiB. They are listed and can be ticked, but aren't ticked by default.

## What this phone can get back

Figures are from the 11-19 report and log. Everything goes to the quarantine first, except the Telegram space, which
is cleared inside Telegram.

| Where | Size | How |
|---|---|---|
| The 11-19 Autopilot's quarantine | 2.2 GiB | History → empty that run. Otherwise it goes by itself 7 days after the run. New runs offer "Free now" in their result. |
| My Files' recycle bin | 242 MiB | Clean-up → Other apps' recycle bins (ticked by default). Removing items there needs a phone check (NEEDS DEVICE, below). |
| LogCat exports | about 100 MiB | Automatic. Each new export is a zip, and the three plain-text ones drop out over the next three exports. |
| Largest files you no longer need | your choice | Storage map → Largest files: tick, then Move to quarantine |
| Telegram's own folder | 466 MiB | In Telegram: Settings → Data and Storage → Storage Usage |
| Thumbnail caches | 18.0 MiB | Clean-up → Thumbnail caches |

Storage isn't short. The report says 19.6 GiB was added in 17 days, about 1.2 GiB a day, and the 108 GiB free lasts
about 93 days at that pace.

## Verification

- Engine tests: 221, all passing. App tests: 15, all passing. Lint: no issues. These are uncached runs
  (`--rerun-tasks --no-build-cache`) at `abfba2a`. With the test fix, both suites pass again with the temp folder on
  ext4 and on tmpfs.
- Failing runs recorded before the fix:
  - `SystemTrashTest`: 2 of 2 failed (`evidence15/01`).
  - The extracted-zip case in `LayoutCleanupTest`: 1 of 21 failed (`evidence15/03`).
  - The CI failure, reproduced on tmpfs (`evidence15/13`).
- The ADDED rows have passing runs only (`evidence15/02`, `04`, `05`, `06`, `07`, `09` to `12`). The evidence logs are
  kept in the session's scratchpad, not in the repository.
- The app flow test renders the new screens: *Largest files* with a pick and with protected rows, and the result
  dialog with "Free now". It moves a camera original to the quarantine and undoes it.

## Build

| | |
|---|---|
| Version | 1.2.15 (code 15), built from `abfba2a` with R8 |
| APK SHA-256 | `213cd140f808ec475cf017a8aefa1e6d35526a4ec8fc80792ecb075b9043ab9e` |
| Signature | v2, one signer, certificate SHA-256 `adcf4d47e294570890625e1c03dbd3b0b13aec82d06eb164b0d11529d1d27897` |
| Checks | `zipalign -c -P 16` OK; `verify-apk.sh` exit 0, including 16 KB alignment of the one native library |

This is the same debug certificate as the 1.2.13 and 1.2.14 builds on the phone, so it installs over 1.2.14 and keeps
its data. It can't update a build signed with the release key, and none is on the phone.

## NEEDS DEVICE

- **Moving items out of `Android/.Trash/com.sec.android.app.myfiles`** with all-files access on Android 17. The scan
  read the folder (198 files), but a move hasn't been tried.
- **What My Files shows afterwards.** Its recycle bin may still list moved items until it refreshes. Undo puts them
  back where it expects them.
- **The Shizuku race** in the library, under "What the log shows" above.
