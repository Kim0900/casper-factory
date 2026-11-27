# GNSSKeeper v0.2.7 — approved repair implementation

Baseline: v0.2.6-test, versionCode 8, source 782c60e9e8554b6ebea92a7647dc0da40e7a6d1b.
This branch implements the approved v1.2-R1 preparation specification. It does not change the production database or merge the main branch.

| Scope | Implementation | Evidence / outstanding checks |
|---|---|---|
| F01 | FINE+COARSE declared and requested together; actual permission rechecked after dialogs/settings; approximate/denied state stops start | Manifest/source; actual Galaxy dialog, revocation and rotation checks pending |
| F02 | UUID/exclusive session directory, no append, one header; epoch-separated recording | RepairRegressionTest rapid sessions, stale callback and primary schema fixtures |
| F03 | All opened writers closed on partial initialization; write/flush/close failures propagate; failed finalization is INCOMPLETE | Injected open/header/close failures; closed-writer rejection |
| F04 | Readiness rechecked in Activity and Service; expected foreground start exceptions handled; START_NOT_STICKY | Source and Android release compilation; real OS/background/settings races pending |
| F05 P1 | Serialized worker owns registration/storage/teardown; main never joins or locks on file I/O; bounded callback queue fails visibly | New 250 ms/1 s/6 s JVM delay fixtures, epoch/backlog/retire tests; these are not Android ANR/Galaxy tests |
| F06 | Fix/status/raw timestamps separate; 5 s freshness; registered differs from receiving; onStopped/provider-disabled invalidates stale data | JVM threshold tests; runtime device checks pending |
| F07 | Notification runtime request on API33+; denial explained and does not block foreground service | Source; actual OS permission matrix pending |
| C01-R1 | Both modes use the same persistent PASSIVE observer/request/executor/schema. ACTIVE only adds separate direct GPS/status/raw paths | gps/network/fused synthetic common-path records and boundary/duplicate flags; actual subscription trace and Galaxy controlled experiment pending |
| C02 | Same package and certificate as v0.2.6; versionCode 9; non-debuggable release variant | Offline signer comparison and final APK verification; actual update/log preservation on Galaxy pending |
| C03-R1 | allowBackup=false plus dataExtractionRules, separately exclude all 9 supported domains for cloud and D2D; no INTERNET permission/exported service/provider | Source policy plus 6 defective fixture rejections; packaged resource check after build. Real cloud restore and Galaxy D2D are NOT RUN |
| C04 | New tests/evidence in a separate set, reproducible commands below; immutable historical evidence retained | Author's own tests/CI are not an independent verifier. Independent replay and actual Galaxy remain pending |

## Primary data interpretation

`observer_passive.csv` is the ONLY comparison location stream in both modes. `diagnostic_gps.csv`, GNSS status and raw summaries are ACTIVE diagnostics, never primary samples. `observer_provider=passive` is separate from `source_provider` (`gps`, `network`, `fused`, etc.). Quality comparisons require the same source-provider stratum and sufficient common samples. No common samples means N/A, not a direct network-versus-GPS accuracy comparison.

A generated experiment ID remains constant over mode switches within one service lifetime. `session_id`, `condition_epoch`, `mode`, start/end monotonic time, sample sequence, fix/receipt monotonic times, duplicate/mock flags and `within_condition` preserve condition boundaries. Rows with `within_condition=false` must be excluded from per-condition quality estimates and reported separately. Start/end plus receipt gaps and the final tail define no-reception intervals, including zero-sample sessions. Missing END/OPEN metadata denotes incomplete recording, not a successful full-duration run. Wall-clock time must not determine experiment duration. Record external N/K strata, route/order, cooldown, OS/One UI, other apps/settings, battery and temperature in a pre-fixed experiment manifest; this app does not infer those conditions.

The common observation path is not proof that Kakao's driver app consumed the same fix. OS estimated accuracy is not absolute positioning error. No Kakao-call reception improvement is established or claimed.

## Reproduce

JDK17, Android platform36/build-tools36.0.0, Gradle9.6.0, AGP9.4.0:

```
python3 verification/check_security.py
gradle :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --no-daemon
```

CI uploads the unsigned RELEASE APK, JUnit reports, lint/merged-manifest evidence and signing tools. Only the existing dedicated private key may sign an installable APK. Never commit signing material or real location traces. Source commit/run ID, final APK hash, certificate, signed/unsigned ZIP payload comparison and decoded rules must accompany the final artifact.

## Storage and backup mapping

| Domain | Actual use / exclusion |
|---|---|
| external | getExternalFilesDir("gnss_logs") session CSV, session.json and session.json.tmp; whole domain excluded |
| file | getFilesDir()/gnss_logs fallback with identical contents; whole domain excluded |
| root | No extra file path used; excluded to cover future/private copies |
| database/sharedpref | No app database/shared preferences used; excluded defensively |
| device_root/device_file/device_database/device_sharedpref | No device-protected storage API used; all excluded defensively |

Both cloud-backup and device-transfer exclude each domain's entire `.` subtree. No include exception. Cache contains no app-authored location traces. No new sharing, external upload, accessibility, broad storage permission or export feature. App-private storage and hashes are not encryption. Set retention/access/disposal policy before real location experiments; use synthetic data in public test evidence.

## Hardware / independent completion gates

Do not report comprehensive PASS until a different verifier reproduces the fixed SHA/APK and an identified Galaxy completes the approved matrix: installation/update and old CSV hash preservation; precise/approximate/deny/revoke; notifications allow/deny; location OFF/ON; rapid mode changes; lock/resume; calls/driver-app switching; >=45 minutes recording and thermal/power checks. Use dedicated synthetic-data devices for actual cloud backup/restore and Galaxy D2D (supported wired/wireless routes), prove transport works with a positive control, and require zero protected markers restored/transferred. Unavailable access, unsupported routes or non-working backup services are NOT RUN, not PASS. Never uninstall the existing app to bypass a certificate mismatch.

## Historical evidence remains separate

S19 results.txt SHA256 fec7a4ace381d2abaf04bc0a3805aced4d7389d4983d15f67575da4f1a395dd7.
SR05 f05_reassessment.jsonl SHA256 a6d01ceafb4b8754884dfeb59ac598f2abe9922677e8aafde5de950c76eacae3.
Those original 19 and 4 results/harnesses are preserved; the new repair tests neither overwrite them nor count their defect-detection asserts as fixed-version passes.
