# Release signing

Android installs an update only when it carries the same signature as the installed app. Every CI build of Galaxy
Steward is signed with one release key, kept in this repository's encrypted GitHub Actions secrets. Each new APK
therefore installs over the last one, and the app keeps its settings, History and permissions.

The key never appears in the repository or in any workflow artifact. The repository is public, and anyone with a
GitHub account can download its artifacts.

## One-time setup (Termux)

Run this in Termux on your phone:

```bash
curl -fsSLO https://raw.githubusercontent.com/Zfkirke0109/Galaxy-Filesystem-Steward/main/scripts/setup-signing.sh
bash setup-signing.sh
```

[`setup-signing.sh`](../scripts/setup-signing.sh) does the following:

1. Installs a JDK (for `keytool`) and the GitHub CLI if Termux doesn't have them, then signs you in to GitHub.
2. Creates an RSA 4096-bit key in `~/.galaxy-steward/release.jks`, with a random password in
   `~/.galaxy-steward/release.password`. Only Termux can read either file.
3. Stores the key as the `GALAXY_STEWARD_KEYSTORE_BASE64`, `GALAXY_STEWARD_KEYSTORE_PASSWORD` and
   `GALAXY_STEWARD_KEY_ALIAS` secrets. `gh` encrypts them on the phone before uploading them.
4. Pins the certificate's SHA-256 fingerprint in the `GALAXY_STEWARD_CERT_SHA256` repository variable. From then on,
   a build signed with any other key fails instead of publishing an APK that can't update the app.
5. Starts a build of `main`, which becomes the first signed release.

Running the script again is safe. It reuses the existing key and never replaces a key the repository already pins.

**Back up `release.jks` and `release.password`** somewhere private, such as a password manager or your own cloud
drive. Don't put them in shared storage, where other apps can read them. GitHub can use the secrets but never shows
them again. If the key is lost, the next build can't update the installed app, and it has to be uninstalled once
(see [Replacing a lost key](#replacing-a-lost-key)).

## Manual setup

On any computer with a JDK:

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias galaxy-steward \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Galaxy Steward"
base64 -w0 release.jks                                          # the GALAXY_STEWARD_KEYSTORE_BASE64 secret
keytool -list -v -keystore release.jks -alias galaxy-steward    # the SHA256 line is the fingerprint
```

Then add these under **Settings → Secrets and variables → Actions** in the repository:

| Name | Kind | Value |
|---|---|---|
| `GALAXY_STEWARD_KEYSTORE_BASE64` | Secret | The keystore file, base64-encoded |
| `GALAXY_STEWARD_KEYSTORE_PASSWORD` | Secret | The keystore password |
| `GALAXY_STEWARD_KEY_ALIAS` | Secret, optional | The key's alias. Defaults to `galaxy-steward` |
| `GALAXY_STEWARD_KEY_PASSWORD` | Secret, optional | The key's own password, only for a JKS keystore where it differs |
| `GALAXY_STEWARD_CERT_SHA256` | Variable, recommended | The certificate's SHA-256 fingerprint (colons and case don't matter) |

## What CI does

The [Android build](../.github/workflows/android.yml) workflow runs for every pull request, every push to `main` and
on demand:

1. It runs the engine tests, the end-to-end app test and lint.
2. It decodes the key into the runner's temporary folder, checks that the password opens it, builds the release
   APK, and deletes the key again.
3. The workflow's run number becomes the version code, and the version is `1.2.<run number>`. Every build is newer than
   the one before, so it installs as an update. Shizuku also restarts the helper with the new code.
4. `apksigner` verifies the signature. The certificate must match `GALAXY_STEWARD_CERT_SHA256` when that is set. The
   job summary shows the version, the certificate fingerprint and the APK's SHA-256.
5. It uploads `galaxy-steward-<version>.apk` as the run's artifact.
6. For `main`, it publishes the APK as the GitHub release `v<version>`, attached as `galaxy-steward.apk`.

When the secrets aren't available (before setup, or for a pull request from a fork), the APK is signed with a
throwaway debug key. The run shows a warning, and no release is published.

## Installing and updating

- **Direct download:**
  <https://github.com/Zfkirke0109/Galaxy-Filesystem-Steward/releases/latest/download/galaxy-steward.apk> is always
  the newest release.
- **Automatic updates:** add `https://github.com/Zfkirke0109/Galaxy-Filesystem-Steward` to
  [Obtainium](https://github.com/ImranR98/Obtainium) or GitHub Store. Each new release then shows up as an update.

**Switching from a build made before signing was set up:** each of those builds was signed with a different
throwaway key, so uninstall it once before installing the first release. The uninstall removes what the app keeps
privately: settings, History with its Undo records, and the hash cache. So **undo any run you still want undone
before you uninstall.** Quarantined files stay in `/storage/emulated/0/.StorageSteward/Quarantine`. After that, every
release installs over the previous one.

## Building locally with the key

Add the key to `~/.gradle/gradle.properties`, never to this repository:

```properties
galaxySteward.keystore=/absolute/path/to/release.jks
galaxySteward.keystorePassword=...
```

`./gradlew :app:assembleRelease` then signs with it. The matching environment variables (`GALAXY_STEWARD_KEYSTORE`,
`GALAXY_STEWARD_KEYSTORE_PASSWORD`, `GALAXY_STEWARD_KEY_ALIAS`, `GALAXY_STEWARD_KEY_PASSWORD` and
`GALAXY_STEWARD_VERSION_CODE`) work too. Without a key, release builds use your machine's debug key.

## Replacing a lost key

Run `FORCE=1 bash setup-signing.sh`. It creates a new key and pins it in place of the old one. The phone has to
uninstall the app once, as described above. After that, updates work again.
