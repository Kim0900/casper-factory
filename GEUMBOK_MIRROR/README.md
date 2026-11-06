# GEUMBOK MIRROR G2 synthetic prototype

Task #232, MAGI authorization #6864. Android 7+ sender and receiver APKs.
This build transmits only an app-generated map with a 24-bit frame marker. It has no MediaProjection entry point, real navigation capture, Kakao T extraction, remote input, Android Auto, audio, cloud service or telemetry SDK.

Connect both stationary test devices to the same private IPv4 Wi-Fi. Prepare an identity in each app, enter the complete public 64-hex confirmation code from the other device, start the phone sender, and connect the receiver to the phone's displayed numeric Wi-Fi address. Codes and TLS identities expire when the session ends. The socket port is 38443. The receiver uses a Wi-Fi Network socket factory. The sender listener binds the selected exact Wi-Fi IPv4 address and rejects off-subnet peers. Network/address replacement ends the session.

The receiver offers fit, centered fill, and manual ROI center/zoom. Resizing changes only its TextureView transform. Visible onPause continues streaming, while onStop, locking or Surface destruction ends the session. Existing Tasker profiles are not changed. The received pane occupies 70% in the driving preset and 30% in the waiting preset. A fixed 5:5 preset is a separately recorded fallback.

Only a socket outage on the unchanged Wi-Fi can retry, at 250/500/1000ms with three attempts and a 15-second episode limit. Every attempt uses a fresh TLS context and mutual SPKI pin validation. Old decoder/transport queues are destroyed and a newly requested IDR is required. Authentication failure, stopping, locking and network replacement end the session. The receiver's missing-frame cover uses the actual TextureView update callback and a conservative timer, to be measured on hardware against the 750ms criterion.

The pipeline uploads one static synthetic map texture, then draws frame markers with GLES. No per-frame bitmap encode/copy path is used. Sender preview updates are asynchronous and their UI scheduling error must be included in any external-camera latency measurement.

Build with JDK21, Gradle9.6.0, AGP9.4.0, SDK36. Run gradle testDebugUnitTest lintDebug :sender:assembleDebug :receiver:assembleDebug. tools/verify_artifacts.py inspects both merged manifests and APK digests. The branch workflow builds unsigned APKs, verifies them, and signs only in a separate CI job using GitHub Secrets. Missing signing Secrets prevent a signed build. These tests do not measure S25+/Tab A performance, split-screen gaps, Tasker interference, thermal behaviour or runtime external egress.

Physical tests await the representative's tablet model/OS. Freeze the authorized acceptance values before those tests. Use the same conditions for OFF/ON 60-minute runs. Log temperature, battery, thermal state and interruption data, retain failed runs, and submit through CASSANDRA then MAGI. Synthetic pipeline results must not be described as successful real-screen mirroring or operational approval.

The pre-registered hardware criteria from #6864 are fixed at p95 latency <=500ms, displayed FPS >=80% of the chosen 15/10fps profile, missing samples <=5%, every one of 20 split resizes <=750ms with zero connection/encoder/decoder recreation, 10 socket recovery trials and a 60-minute continuous run. OFF/ON thermal delta is <=5 degrees C, final 10-minute ON temperature rise <=1 degree C, and additional ON battery depletion <=5 percentage points/hour. OS thermal SEVERE ends the test. Measure the 750ms stale-frame cover separately from resize interruption. Tasker interference must be zero. No hardware result has been claimed.

The signing certificate 10731e0ea7f530b66e8a160121fec5c4e0b9c82741c68e09f092162891e5d570 is exposed and retired. Every previous APK signed by that certificate is prohibited for real use. The new CI signing identity must differ. Deleting the file in the current tree cannot retract historical copies.
