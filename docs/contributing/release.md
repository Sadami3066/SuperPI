# Release Engineering

SuperPI uses **tag-driven GitHub Actions** across the monorepo. Because the Android app's in-app updater monitors `/releases/latest` on `Sadami3066/SuperPI`, only Android tags (`vX.Y.Z`) promote a release to **Latest**; all other components publish with `--latest=false`.

## Tag & Workflow Matrix

| Target | Tag Pattern | Workflow | Artifact / Output | Marks `Latest`? |
|---|---|---|---|---|
| **Android App** | `vX.Y.Z` (e.g. `v2.1.0`) | [`release-android.yml`](../../.github/workflows/release-android.yml) | Signed `PI-X.Y.Z.apk` + `SHA256SUMS.txt` | **Yes** |
| **Desktop** | `desktop-vX.Y.Z` | [`release-desktop.yml`](../../.github/workflows/release-desktop.yml) | `superpi-desktop-X.Y.Z-linux-x64.tar.gz` + `SHA256SUMS.txt` | No |
| **Relay** | `relay-vX.Y.Z` | [`release-relay.yml`](../../.github/workflows/release-relay.yml) | Docker `ghcr.io/sadami3066/superpi-relay:X.Y.Z` & `:latest`, tarball | No |
| **Plugin API / Design** | `plugin-api-vX.Y.Z` | [`release-plugin-api.yml`](../../.github/workflows/release-plugin-api.yml) | Pushes AAR/POM/sources to `SuperPI-Maven` | N/A |
| **Official Plugins** | `plugin-<module>-vX.Y.Z` | [`release-plugin.yml`](../../.github/workflows/release-plugin.yml) | Signed `<module>-<ver>.ddp`, checksums, optional Market PR | No |
| **KotlinSDK** *(companion repo)* | `vX.Y.Z` | `SuperPI-KotlinSDK` `release-maven.yml` | Pushes JVM/Android SDK artifacts to `SuperPI-Maven` | Yes (on SDK repo) |

All release workflows also support manual `workflow_dispatch` fallback from the GitHub Actions tab.

## Android App Release Contract (`vX.Y.Z`)

1. Sync `apps/android/app/src/main/assets/market/registry.json` (+ `.sig`) from `SuperPI-Market` if updating bundled catalog seeds.
2. Update `versionCode` (+1 strictly higher than prior release) and `versionName = "X.Y.Z"` in `apps/android/app/build.gradle.kts`, plus `CHANGELOG.md`.
3. Create **user-facing** notes at `release-notes/vX.Y.Z.md`:
   - The in-app update dialog renders the GitHub release body verbatim — write clear user notes, never raw git commit logs or "see assets below".
   - Never mark stable Android releases as draft or prerelease.
4. Run `./apps/android/scripts/check.sh --full` locally, smoke-test the minified `assembleRelease` APK on a real device (including official plugin import/UI + upgrade over previous signed APK), then push commit + tag:

```bash
git tag v2.1.0
git push origin main v2.1.0
```

### Android Release Signing & R8 Notes

- **Required Secrets on `Sadami3066/SuperPI`**:
  - `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
- **Certificate Continuity**: Changing the release keystore breaks in-place Android upgrades (`android:allowBackup="false"`, so uninstalling wipes local sessions/keys). Keep `~/.config/superpi/secrets/android-release.keystore` backed up offline.
- **R8 Keep Rules**: Always test minified release builds (`isMinifyEnabled = true`) when adding reflection entries, WorkManager/Room consumers, or new `:plugin-api` dependencies (`apps/android/plugin-api/consumer-rules.pro`, `apps/android/app/proguard-rules.pro`).

## Maven Artifact Publishing (`SuperPI-Maven`)

Both `SuperPI` (`release-plugin-api.yml`) and `SuperPI-KotlinSDK` (`release-maven.yml`) push artifacts over SSH using a dedicated write-scoped deploy key (`MAVEN_DEPLOY_KEY` secret paired with `~/.config/superpi/secrets/maven-deploy-key.pub` on `Sadami3066/SuperPI-Maven`).

## Official Plugins (`release-plugin.yml`)

- Uses `PLUGIN_SIGNING_KEY` (Ed25519 PKCS#8 PEM; public key derived in CI via `openssl pkey -pubout`).
- Tagging `plugin-feishu-v2.8.0` builds, tests, signs, and attaches `feishu-<version>.ddp` + SHA-256 summary.
- Using `workflow_dispatch` with `module: all` bundles all 10 official plugins into `plugins-YYYYMMDD`.
- If `MARKET_PUSH_TOKEN` (optional fine-grained PAT scoped to `Sadami3066/SuperPI-Market` contents & pull requests) is set, the workflow automatically uploads `.ddp` assets to `SuperPI-Market` Releases and opens an entry-update PR.

## Local Release Smoke Commands

```bash
# Local signed release APK (reads ~/.config/superpi/secrets/keystore.properties automatically)
cd apps/android && ./scripts/build.sh release

# Local signed plugin bundle verification
cd plugins/official && ./scripts/gradlew-locked :feishu:verifyPlugin --no-daemon
```
