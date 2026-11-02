# GNSSKeeper v0.2.8 — explicit log export

The app-private logs in v0.2.7 could not be conveniently retrieved on modern Android. v0.2.8 adds a non-exported export Activity, opened by the main screen's “로그 내보내기” button. The source recording format/paths and GNSS request behavior are unchanged.

## User flow

1. Stop recording and wait for “중지”.
2. Open “로그 내보내기”, choose one or more sessions (or “전체 선택”).
3. The export worker creates and verifies a private temporary ZIP.
4. Android ACTION_CREATE_DOCUMENT lets the user choose a specific ZIP destination, such as Downloads. No broad storage/tree permission, persistent URI permission, exported provider or automatic network upload is added.
5. The worker copies the ZIP, closes the output, reads the selected document back and compares SHA256 before reporting success. The original CSV/JSON files remain unchanged.
6. The user can attach/share the ZIP from the chosen file location. There is no automatic recipient or background transfer.

## Contents and boundaries

Only selected direct session directories under getExternalFilesDir("gnss_logs") or getFilesDir()/gnss_logs are eligible. Known CSV/JSON names are allowlisted, including the legacy session-name CSV. No recursive directory export. Roots, session directories and source files reject symbolic links. The archive separates external/internal sessions and preserves source bytes/timestamps. `export_manifest.json` records app version, selected IDs, metadata state, per-file size and SHA256. OPEN/MISSING/UNKNOWN/INCOMPLETE states are preserved and must not be interpreted as complete experiment evidence. No coordinates are placed in status/error messages.

The ZIP is not encrypted. The app tells the user that it contains location/time data before destination selection. The user's selected provider controls the exported copy's storage; automatic app cloud/D2D backup exclusions remain unchanged and do not apply to user-chosen exported copies. Retention/access/sharing decisions belong to the user. No backup exclusion is weakened.

Private temporary ZIPs live only in cacheDir/log_exports, are removed after cancellation/save/failure/Activity exit, and stale spools are cleared on the next fresh export scan after process loss. Source logs are never removed. If the process or selected document provider fails during a save, an incomplete ZIP may remain at the user-selected destination. The app reports this without claiming success or deleting a potentially pre-existing provider document. A close/commit error or read-back mismatch fails the save.

## Lifecycle and threading

Scanning, compression, hash verification, document I/O and cache cleanup run on a dedicated worker. The UI never waits for file I/O. A retained task uses application context, detaches destroyed Activity listeners, and keeps the document-picker state across configuration recreation. Saved state can recover a prepared spool while the picker is open. Process death during writing is reported as interrupted/unverified; it is not resumed as a completed save. Main recording can only start in its own UI; the export screen requires the recorder to be stopped. Closed recording sessions are never appended.

## Tests

- Existing 12 repair regression tests are retained.
- LogExporterTest: multi-session exact bytes/hashes, selection/allowlist, internal/external/legacy roots, missing source, linked directories/files/root replacement, preparation cancellation, corrupted/tampered destination/spool, write/close failure, save cancellation and duplicate selections.
- LogExportActivityTest: Robolectric Android API31 and API36 simulation covering active-recording rejection, ZIP picker request, picker cancellation, configuration recreation without duplicate picker, a selected document round trip and provider-open failure. Simulated Android tests are not a real Galaxy/provider or independent verifier.
- Static backup/security checks still reject the existing six defective fixtures; they additionally require the export Activity to be non-exported and forbid broad storage permissions.

Reproduce with JDK21, SDK36, Gradle9.6.0, AGP9.4.0, Robolectric4.17:

```
python3 verification/check_security.py
gradle :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --no-daemon --continue
```

The new APK keeps package kr.magi.gnsskeeper and the v0.2.6/v0.2.7 dedicated certificate; versionCode10/versionName0.2.8. It is intended as an in-place v0.2.7 update. Actual Galaxy update/data preservation, Samsung document-picker/provider behavior and physical long-duration runs remain pending device evidence. Kakao-call improvement remains unestablished. Historical S19 and SR05 originals are not modified or combined with new test counts.
