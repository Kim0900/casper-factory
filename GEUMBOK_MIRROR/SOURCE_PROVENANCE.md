# GEUMBOK MIRROR source provenance

EDISON wrote this synthetic-only implementation for Task #232 under MAGI event #6864.
ScreenOnAuto public descriptions supplied feature concepts. No ScreenOnAuto APK was decompiled and no code, assets, strings, signatures or resources were copied. ScreenStream was examined only for the previously approved comparison. No ScreenStream or AA Suite code is included.

The map bitmap, frame markers, UI and protocol are newly authored. Android platform APIs provide rendering, MediaCodec and networking. Bouncy Castle bcpkix/bcprov/bcutil 1.86 provide certificate construction under the Bouncy Castle licence at https://www.bouncycastle.org/licence.html. Their licence notices are retained in the dependency JAR/APK metadata. JUnit 4.13.2 is a build-time test dependency under EPL 1.0.

The committed PKCS12 is a fixed, public, test-only APK signing identity, with the conventional debug password android. It must never sign a production release. TLS identities are unrelated ephemeral EC keys created per prepared session and never written to disk. Closing releases references to managed key objects, not a guarantee of hardware memory zeroization.
