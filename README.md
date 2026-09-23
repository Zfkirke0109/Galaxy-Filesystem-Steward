# Galaxy Filesystem Steward

An Android app that cleans, deduplicates, organizes and optimizes your phone's shared storage. It's a native
port of the **Koa Whole-Device Storage Steward v18.2** Termux script, with the same safety rules: scans never
change anything, you approve every change, each file is re-checked right before it's touched, and every run can be
undone.

Built with Kotlin and Jetpack Compose (Material 3 / Material You). Minimum Android 8.0, targets Android 16, and
tuned for Galaxy devices such as the S23 series.

<p>
  <img src="docs/screenshots/home.png" width="200" alt="Home dashboard after a scan">
  <img src="docs/screenshots/duplicates.png" width="200" alt="Duplicate review showing which copy is kept">
  <img src="docs/screenshots/organize.png" width="200" alt="Smart organize plan grouped by destination">
  <img src="docs/screenshots/autopilot-result.png" width="200" alt="Autopilot result with an Undo button">
</p>

<sub>Screenshots are rendered by the end-to-end test from a sample storage layout.</sub>

## What it does

| Area | What it finds | What happens when you approve it |
|---|---|---|
| **Duplicate files** | Exact duplicates anywhere in shared storage. Files are grouped by size, then compared by a quick head+tail fingerprint, then by full SHA-256, with parallel workers and a persistent hash cache. | Keeps the best copy (camera roll before Pictures, organised Documents before Download, an original name before "(1)"/"copy", the oldest before newer). Both copies are re-hashed right before the extra one is deleted, or quarantined if you prefer. |
| **Duplicate folders** | Whole folder trees that are identical (names, sizes and content). Only the top-most matching pair is reported. | Removes the redundant tree file by file, checking each file against the kept tree. |
| **Folder merges** | Folders that share most of their content, e.g. `Download/Trip` and `Pictures/Trip`. | Moves the files that are unique into the kept folder and removes the verified duplicates. |
| **Clutter** | Abandoned partial downloads, old `/log` dumps, empty folder trees, gallery trash, `.thumbnails` caches, zero-byte files, APKs for apps already installed, and folders left behind by apps you've uninstalled. | Quarantined, so you can restore them until the quarantine is emptied (manually or after a retention period). Empty folders are removed. |
| **Smart organize** | Treats `Download` as an inbox. Every file and folder gets a permanent home based on its name, type and content: finance, travel, health, device firmware, diagnostics, APKs, archives, e-books, AI models, ROMs, backups, and more. | Moves files with no overwriting: identical files at the destination are deduplicated, and different files with the same name get a hash suffix. Folders move as a unit. Wrapper folders like `Documents/Documents` are dissolved. |
| **Optimize** | Nested duplicate-name folders (`X/X/...` left by extracted archives), very large flat folders, very deep paths, and low free space. | Collapses the redundant levels and sorts huge folders into year (or month) buckets. The rest is reported as advice. |
| **Storage map** | Folder-by-folder breakdown with size bars, largest files, a breakdown by file type, and the ownership zone of each folder. | Read only. |
| **History** | Every run, with what it freed, moved, deduplicated or quarantined. | **Undo** replays the journal backwards and checks each step before reverting it. Quarantine can be emptied per run or all at once. |
| **Weekly audit** | Optional read-only scan while the phone charges. | Sends a notification saying how much space you could reclaim. It never changes files, except emptying quarantines that are past their retention period. |

**Autopilot** on the home screen applies everything that's currently selected in one confirmed run. It works in the
safest order: dedupe, then clutter, then layout fixes, then filing.

### Where things get filed

```
Documents/
  Reference/  Spreadsheets/  Presentations/  Data/  Books/  Fonts/  Design/  3D-Models/
  Reports/  Reports/Diagnostics/  Research/
  Personal/Finance/  Personal/Travel/  Personal/Health/  Personal/Career/  Personal/Contacts-Calendar/
  Software/APKs/  Software/Installers/  Archives/  Backups/
  Development/Scripts/  Development/Build-Artifacts/  Development/Android/
  Android-Device/<your-device>/Firmware/      <- device label is detected, e.g. Samsung-SM-S918B
  Security-Reversing/  AI-Models/  Audio-DSP/  Gaming-Emulation/  Gaming-Emulation/ROMs/
  Inbox-Review/                               <- unrecognised files; not selected by default
Pictures/Imported/<year>/   Pictures/Screenshots/   Movies/Imported/<year>/   Music/Imported/   Recordings/Imported/
```

You can add your own keyword and file-type rules in **Settings → Custom filing rules**. They run before the
built-in ones, for example `acme, invoice*` + `pdf` → `Documents/Work/ACME`.

## Safety model

These rules come from the Termux steward and are enforced both when the plan is built and again right before each
operation runs:

- **Scans are read-only.** Nothing changes until you confirm a dialog, which replaces the script's confirmation
  tokens (`CLEAN_SAFE`, `SMART_ORGANIZE`, and so on).
- **Ownership zones.** `Android/` (data, obb, media) is strictly off-limits, and app-owned copies are never used
  as the "kept" copy of a duplicate. Projects (`.git`, Gradle, Cargo, `pyproject`, `package.json`+`src`, and so on) and
  folders you pin in Settings are never moved or removed, though they can serve as the kept copy. Media-library copies
  are only removed when the kept copy is also in the media library.
- **Credential-like files are never touched**: keystores, `.pem`/`.key`/`.p12`, `.env`, SSH keys, `.kdbx`,
  `.ovpn`, recovery codes, wallets, and anything with "secret" or "credential" in the name.
- **Checks right before each change.** Paths must be canonical and free of symlinked parts. File size and
  modification time must match the scan. Before any deletion, both copies are re-hashed with a fresh SHA-256.
  Moves never overwrite anything. Files changed within the last 10 minutes are left alone.
- **Journaled and undoable.** Each verified change is written to a TSV journal before the next one starts, so a run
  that's interrupted or cancelled still has an accurate record. Undo recreates deleted duplicates from the kept copy,
  moves files back, and restores quarantined items.
- **Quarantine** lives at `/storage/emulated/0/.StorageSteward/Quarantine/<run>/` on the same volume, so
  quarantining is an instant rename. A `.nomedia` marker keeps galleries from indexing it.

## Install

1. Open the latest run of the **Android build** workflow in this repository's **Actions** tab and download the
   `galaxy-steward-apks` artifact.
2. Install `app-release.apk`. The CI build is signed with a debug key; sign your own build if you plan to
   distribute it. You'll need to allow installs from your browser or file manager.
3. Open the app and grant **All files access**. The app has no internet permission, so nothing leaves your phone.

## Build from source

Requirements: JDK 17 or later and the Android SDK (platform 36).

```bash
./gradlew :core:test              # engine unit tests (plain JVM, fast)
./gradlew :app:testDebugUnitTest  # end-to-end app test (Robolectric) + screenshots in app/build/outputs/roborazzi
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease    # minified with R8
```

The engine tests run against real temporary directories. They cover keeper choice, the protected zones, fresh
SHA-256 re-checks that catch content changed after a scan, quarantine, folder deduplication and merges, filing
rules, clutter detection, layout fixes, and undo. The app test scans a sample phone layout through the real
ViewModel, opens every screen, applies Autopilot, checks the result file by file on disk, then undoes the run and
checks that everything was restored.

## Architecture

```
core/   Pure Kotlin/JVM engine, no Android dependencies, unit-tested against real temp directories
  scan/       TreeScanner: walks storage without following symlinks; assigns ownership zones and safety flags
  hash/       Quick fingerprints, SHA-256, persistent hash cache
  dedupe/     DuplicateFinder, FolderAnalyzer (exact trees and partial overlaps), keeper ranking
  junk/       JunkPlanner
  organize/   Keyword and extension rules, OrganizePlanner (the semantic layout planner)
  optimize/   OptimizePlanner (flattening, date buckets, health insights)
  exec/       ActionExecutor, PathGuard, JournalStore, RollbackEngine, QuarantineManager
app/    Android app: Compose UI, ViewModel, settings, WorkManager weekly audit, MediaStore rescans
```

### From the Termux script to the app

| Termux mode | In the app |
|---|---|
| `audit`, `catalog` | Smart scan, storage map, health insights |
| `duplicates`, `steward-dedupe` | Duplicates → Files / Folders / Merges |
| `organize-plan`, `organize-smart SMART_ORGANIZE` | Smart organize |
| `rollback-organize` | History → Undo (covers every kind of run) |
| `safe`, `quarantine-logs`, `all-safe` | Clutter (stale downloads, old logs, empty folders, …) |
| `autopilot BALANCED_AUTOPILOT` | Autopilot card on the home screen |
| hash-hint cache and benchmark-guided workers | Persistent hash cache and CPU-scaled parallel hashing |

### What's deliberately left out

Some of the script's Termux-specific jobs aren't possible, or aren't safe, from a normal Android app: clearing
other apps' private caches, `pm trim-caches`, Termux and proot toolchain cleanup, and pruning Git repositories. An app
without root or Shizuku can't reach those areas. The strict protections for Amazon Music, Audible and Facebook still
apply, because the app never touches `Android/data`, `obb` or `media` at all.

On "faster I/O": the app speeds up everyday file access by freeing space (flash storage slows down and wears
faster when nearly full), by removing duplicate and junk files that galleries and file managers would otherwise index,
and by splitting very large flat folders. It doesn't change filesystem settings or run TRIM, which Android
already schedules itself.
