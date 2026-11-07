# Task #232 — hotspot sender route (MAGI #6959)

The sender supports a manually enabled hotspot on Android16/API36. Synthetic frames only; no other-app data, destinations, coordinates, capture, remote input or tethering control.

## Authority and permissions

The public `TetheringManager.registerTetheringEventCallback` supplies currently tethered interfaces. `HotspotMonitor.Api36` accepts only `TETHERING_WIFI` entries and inspects those exact names with `NetworkInterface.getByName`. No name-prefix guessing, default-network or upstream-Wi-Fi fallback, reflection, hidden API, shell, scan, MAC lookup or wildcard listener.

Exactly one up, known-index RFC1918 IPv4 host/prefix is required. Public/link-local/loopback/IPv6, missing or ambiguous addresses, network/broadcast addresses and invalid prefixes fail closed. Index, address and prefix are fixed for the session; a restored hotspot does not revive an invalidated route.

**Additional permissions:0.** Public API36 tethering status registration requires the existing `ACCESS_NETWORK_STATE`; the manifests remain `INTERNET` + `ACCESS_NETWORK_STATE`. No privileged, location, nearby-device, accessibility or overlay permission. The app never enables or disables hotspot itself.

Official sources:
- https://developer.android.com/reference/android/net/TetheringManager
- https://developer.android.com/reference/android/net/TetheringManager.TetheringEventCallback
- https://developer.android.com/reference/android/net/TetheringInterface
- https://developer.android.com/reference/java/net/NetworkInterface

## Binding and shutdown

The listener binds to the AP's exact selected private IPv4 at port38443. Accepted TCP/TLS sockets must have that local address and a private same-subnet peer. The AP route cannot initiate an outbound connection. Existing TLS1.2, ECDHE_ECDSA, mutual authentication and full64-digit SPKI pins remain.

`RouteLease` owns listener/raw/TLS sockets and closes them synchronously before notification. Sender route invalidation stops its encoder and clears its queue without waiting for UI dispatch. The route identity check prevents an old callback from closing a newly prepared server.

Interface-set changes revoke on the system callback. A dedicated worker checks address/up/index/prefix every100ms; send-loop validity rejects snapshots older than500ms. These are software intervals, **not measured Android event-delivery or physical shutdown upper bounds**. OS scheduling/API latency must be measured on the S25+. Shutdown never follows another address/network, and the terminal lease requires new manual preparation/pins. Existing same-route socket-only retries retain250/500/1000ms, max3/15s, fresh TLS and IDR.

## Compatibility and signing

API36 classes exist only in the sender module and the entry point checks `SDK_INT >= 36`. Receiver minSdk24 remains; compiled receiver/common project classes must contain no tethering or sender-monitor reference. The APK gate also rejects sender-monitor inclusion. D8 already bundles synthetic framework throwing stubs in the approved baseline (receiver `classes5.dex`, SHA256 `238a5c8606074db0fd8800daf30296e7e4fbd67620ccd357dd2cae743e5db8f4`). The gate recognizes only a separate framework/R8 synthetic container and verifies every tethering definition is a synthetic initializer throwing `NoClassDefFoundError`, with no tethering field or functional method reference. This static check does not replace API27 device execution. Its Wi-Fi `Network` client, video/display and codec behavior remain. The shared base adds socket ownership for cleanup. Sender on older Android can explicitly use the common-AP mode; hotspot mode reports unavailable.

Both APKs are versionCode3, `0.1.2-synthetic`, still debug synthetic test builds. The existing three signing Secrets/certificate and old-certificate rejection are retained. Workflow signing adds this exact branch to its allowlist, without a branch wildcard, fork or pull_request Secret path.

## Procedure delta

1. In Settings, manually enable S25+ hotspot and connect SM-P580 before opening the apps. Confirm S25+ Android16/API36+.
2. Sender defaults to **휴대폰 핫스팟(AP) · Android 16 이상**. Wait briefly for OS status, then prepare. The address label is **핫스팟 AP 주소**. Preparation failure has no guessed-IP fallback.
3. Receiver remains a Wi-Fi client. Prepare both, exchange the current64-digit codes, enter the phone's displayed AP address on the tablet, then start sender and connect receiver. APK certificate fingerprint is not the pairing code.
4. Home router tests must explicitly select **공용 AP · 두 기기 같은 Wi-Fi**. Diagnostics distinguish `ROUTE_COMMON_AP` and `ROUTE_HOTSPOT_AP`; neither result substitutes for the other.
5. Mode change ends the session. Hotspot off/on, address replacement, lock or invisibility requires new preparation. Existing fit/fill/region/split controls remain. Save diagnostics after the timed test: the document picker may end a session.

## Test evidence and limits

`ApRouteTest` covers authorized AP versus upstream/cellular/VPN/USB candidates; down/unknown-index/public/ambiguous addresses; index/address/prefix replacement; terminal restoration; late registration; same-subnet peer restrictions. Its hotspot-off fixture calls the **production snapshot-to-lease function** with a real loopback listener and an established mutually pinned TLS session, checking synchronous socket closure before notification and no revival.

That fixture simulates the OS status event. It does **not** toggle a physical Samsung hotspot. CI JVM results, manifests, DEX inspection, APK hashes and actual signature results must accompany the PR; this document claims no device success.

After CASSANDRA review, physically connect S25+ AP → SM-P580/API27, turn AP off, collect `AP_ROUTE_INVALIDATED_TERMINAL` and timing, and confirm restored AP cannot reconnect without new pins. Full Settings can independently cause `NOT_VISIBLE`; distinguish that event from an AP-specific result. Address-change/permission-loss tests need device evidence when reproducible. Capture synthetic screens only, excluding unrelated notifications.

External egress0 remains a design requirement. A local picture is not packet-level proof; physical packet/thermal/performance/split/Tasker tests remain pending. Real navigation/Kakao T capture, vehicle and commercial use remain forbidden. MAGI merges; no self-PASS or deployment.
