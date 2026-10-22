# Android / .NET Interoperability

This opt-in suite runs the Android SDK's real Retrofit/OkHttp GZIP transport and encrypted Room stores against the existing ASP.NET Core API over Kestrel and real PostgreSQL. It does not use MockWebServer, an in-memory database, or a replacement sync endpoint. Two independent local replicas run on one emulator; this is not a two-physical-device UI demonstration.

## Safety Boundary

The standalone `tools/OfflineSync.InteropHost` project is a test harness, not a deployment host. It runs in Development on localhost port 5087 with known, test-only JWT and cursor keys. Never expose or deploy it. Production API authentication is unchanged. Tests create short-lived signed tokens in memory with isolated workspace UUIDs and replica claims. The host validates signatures, issuer, audience, and expiry. The known key is intentionally public test data, not a production credential.

The disposable Compose service binds PostgreSQL to localhost port 55432, stores its database in tmpfs, and uses a known test-only password. It does not reuse the main Compose volume. The host applies migrations but never deletes or resets a configured database; use only a dedicated test database. Test workspaces/receipts remain until that disposable database is stopped. Do not point the harness at production.

## Run Locally

Requires Docker Desktop running, .NET 10, JDK 21, Android SDK 35, and a running API 26+ emulator. Commands below run from the repository root. Port 5087 and 55432 must be free; no fallback silently targets another server.

```sh
docker compose -p offline-sync-interop -f tools/compose.interop.yaml up -d --wait
```

In another terminal, start the API harness and wait for its readiness message:

```sh
SYNC_TEST_POSTGRES='Host=127.0.0.1;Port=55432;Database=offline_sync_interop;Username=offline_sync_interop;Password=interop-only-password' \
  dotnet run --project tools/OfflineSync.InteropHost
```

Run the explicitly selected interoperability class on the emulator. The debug sample permits cleartext only on explicit emulator/loopback origins; tests use `http://10.0.2.2:5087/`. This setup does not validate TLS.

```sh
ANDROID_SERIAL=emulator-5554 \
ANDROID_HOME="$HOME/Library/Android/sdk" \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./android/gradlew -p android \
  '-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m' \
  :sample:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.offlinesync.sample.ServerInteropTests \
  -Pandroid.testInstrumentationRunnerArguments.serverInterop=true
```

Without `serverInterop=true`, these two tests explicitly skip. The four ordinary sample tests remain independent of server availability. Reports appear under `android/sample/build/reports/androidTests/connected`.

Stop the host with Ctrl+C, then remove the disposable database:

```sh
docker compose -p offline-sync-interop -f tools/compose.interop.yaml down
```

## Assertions

- A real API push commits; a transport wrapper discards its response and throws a simulated lost acknowledgment. The encrypted store reopens, retries the identical request after its persisted deadline, and the server still exposes exactly one initial change/revision. This injects loss after a real commit, not a physical socket failure.
- Independent replica stores exchange initial state, edit offline, push owned counter components and independent tag additions, and converge after pull. Their quantities total three rather than double-apply the replayed initial increment.
- An observed tag removal and a concurrent permanent deletion converge without resurrecting the item. Local queue entries are checked for successful receipt application.
- The real API rejects a JWT replica mismatch; another workspace sees no changes and cannot use the first workspace's signed cursor.

The replay test advances only its injected queue/response clock to the persisted retry deadline, avoiding a sleep. It does not advance server time. It checks database reopen, not actual Android process death. Separate-installation convergence and live WorkManager recovery use the additional procedures below. Redis outage, server restart, physical packet loss, long offline histories, and production authentication/TLS remain outside these tests.

## Verification Status

### Forced Doze And Reboot

`tools/os-recovery.py` extends the network test with persisted WorkManager request identity and boot-count checks. Use a fresh dedicated API 26+ emulator installation for each scenario, no saved sample connection, and an empty sample queue. Start the Kestrel/PostgreSQL harness above and build both APKs with the Separate Android Installations command below. Run each pair on its own fresh installation:

```sh
python3 tools/os-recovery.py emulator-5556 doze prepare
python3 tools/os-recovery.py emulator-5556 doze verify

# On another fresh dedicated installation:
python3 tools/os-recovery.py emulator-5556 reboot prepare
# Wait for adb availability AND sys.boot_completed=1; do not reinstall APKs.
python3 tools/os-recovery.py emulator-5556 reboot verify
```

Preparation checks boot/phone-service readiness and, for Doze, requires deep idle to be enabled before installing APKs or changing settings. Check with `adb -s emulator-5556 shell dumpsys deviceidle enabled deep`. If disabled, explicitly enable it on the dedicated emulator with `adb -s emulator-5556 shell dumpsys deviceidle enable deep` and restore it afterward with `adb -s emulator-5556 shell dumpsys deviceidle disable deep`. The coordinator does not change that setting.

Preparation installs APKs without clearing data, saves the original radio settings in a temporary host JSON file, disables Wi-Fi/mobile data, and runs the real repository scheduling callback. The offline phase checks pending state/zero claims for ten seconds and persists the original work UUID, mutation ID/hash, and Android boot count. Doze preparation backgrounds the sample, simulates an unplugged battery, turns the screen off, forces deep idle, and requires Android to report `IDLE`. Verification requires it still reports `IDLE`, then exits forced idle, resets the simulated battery, and wakes the screen. Reboot preparation instead invokes a real `adb reboot`, leaving radios disabled while Android restarts.

The checkpoint explicitly launches instrumentation, requires no active network, and checks the identical pending mutation with zero claims and the same original work UUID still enqueued. Reboot requires a strictly increased Android boot count; Doze requires the count is unchanged. Connectivity is then restored. Verification does not request another sync: real WorkManager must complete the original request, deliver the unchanged mutation in one claim, and expose exactly one matching API change/revision with equal full document state. No WorkManager test driver, direct runner, injected clock, forced JobScheduler run, or relaxed production constraint is used.

These are recovery-after-transition tests with offline work across the transition. Radios remain disabled throughout forced idle, so this does not isolate Doze network suppression, test maintenance-window delivery, or prove execution during idle. Explicit instrumentation launch and an unlocked emulator mean this also does not prove autonomous boot-receiver delivery, pre-unlock key access, real-world idle timing, OEM battery policies, or physical-device behavior. Credentials are short-lived test tokens written directly to the vault; the connection UI is not tested.

After a successful verify, the coordinator restores original radio settings and removes its host cleanup record. If interrupted between stages, run `python3 tools/os-recovery.py emulator-5556 doze cleanup` (or `reboot cleanup`) after Android finishes booting. Cleanup exits forced idle/resets simulated battery for Doze and restores radios; it does not clear app data or cancel existing work. The script refuses an unfinished preparation record. If cleanup fails, the record remains for retry. Use only a dedicated emulator: screen, battery simulation, network state, and reboot affect the whole device. Reserve adequate disk space: the default partition requested about 12 GB; reducing a fresh AVD's configured partition to 2 GB resulted in an approximately 6 GB minimum partition for this system image.

Both staged scenarios passed locally on 2026-10-21 using `OfflineSync_Doze_API34` / Android 34 against real Kestrel/PostgreSQL, with sample app data cleared between scenarios. Doze preparation confirmed deep `IDLE`; its offline, checkpoint, and reconnect phases each reported `OK (1 test)` in 11.736, 3.014, and 6.634 seconds respectively. Reboot used an actual `adb reboot` without reinstalling between stages and required an increased boot count; its phases reported `OK (1 test)` in 13.223, 13.221, and 8.406 seconds. Both checked the original work UUID, unchanged mutation ID/hash, zero offline claims, one delivery claim, successful original work completion, and exactly one matching API revision with equal full document state. These times are observations, not performance targets. Original radio settings were restored, and deep Doze was returned to its original disabled setting after the Doze scenario.

Earlier disk/phone-service setup failures were not passing tests. The first resumed Doze attempt passed its offline phase but could not enter idle because deep Doze was disabled; cleanup restored its radios. The coordinator now rejects disabled or unrecognized deep-Doze readiness before installation, radio changes, or mutation creation, and still checks for the exact `Service phone: found` response before touching settings. Host-side regression coverage for that early rejection runs without an emulator using `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p test_os_recovery.py -v`; it passed for disabled, empty, and unexpected readiness responses. APK/test assembly passed again; the previously recorded sample lint result was not rerun for this coordinator-only change.

### Active Request Process Termination

`ActiveRequestInteropTests` uses the real SDK HTTP transport and encrypted store with a localhost-only response gate. Start the dedicated Kestrel/PostgreSQL harness above, fully boot a dedicated emulator, and build both APKs with the Separate Android Installations command below. Leave localhost port 5088 free, then run:

```sh
python3 tools/active-request-kill.py emulator-5556
```

The coordinator installs APKs without clearing data and allocates a fresh workspace/account store. A proxy bound to `127.0.0.1:5088` forwards exactly one bounded GZIP push to the real API on port 5087, including its signed test token. It reads the successful backend response but sends no response headers/body to Android. Only after that response arrives does the coordinator force-stop the sample, confirm the process has exited, and close the held connection. The first instrumentation run must be interrupted, not pass. No token or request/response body is logged. Do not expose or deploy this gate.

Verification explicitly relaunches instrumentation, requires a different process ID, and checks that the original mutation ID/hash remains in-flight with one claim attempt. A real pull confirms one committed change at revision one before replay. The test advances only its injected queue/response clock to the persisted lease expiry, replays through the real API directly, and asserts the identical mutation becomes synced with two total claims and unchanged server history/full document state. This tests actual process termination during an outstanding HTTP request, not a synthetic client exception. It does not test elapsed-time lease scheduling, WorkManager delivery, killing a partial upload, OS low-memory termination, reboot, or delivery while force-stopped. Earlier worker tests cover real elapsed lease recovery separately.

The proxy waits at most 30 seconds while withholding the response; the coordinator rejects a termination window of 20 seconds or longer and allows at most 90 seconds for the backend response. Network/client timeouts are unchanged. The verification invocation is bounded to three minutes. Coordinator cleanup releases the gate and stops an unfinished push instrumentation process. Emulator disks/account stores are retained; the sample's saved connection is not replaced. Use only a dedicated test emulator: force-stop affects all work in the sample process, including unrelated sample jobs.

The coordinator passed locally on `OfflineSync_Second_API34` / Android 34 against real Kestrel/PostgreSQL: it held a committed response, force-stopped Android, confirmed process exit, and verification reported `OK (1 test)` in 3.604 seconds. Verification required a changed process ID, the original in-flight mutation/hash with one claim, one already-committed server revision, and successful identical replay with two total claims and no additional revision. The time is an observation, not a performance target. Both original live-server tests also passed again after adding the optional proxy origin to the shared transport helper. APK/test assembly passed; no production implementation or timeout was changed.

### Offline Network Reconnection

`WorkManagerInteropTests.offlineEditDeliveredAfterNetworkReconnect` uses real Android connectivity constraints and the sample's production WorkManager host. Start the dedicated API/PostgreSQL harness above and a fully booted, dedicated emulator with no saved sample connection and an empty sample queue. Build APKs with the command below under Separate Android Installations, then run:

```sh
python3 tools/network-reconnect.py emulator-5556
```

The coordinator installs APKs without clearing data, records Wi-Fi/mobile-data settings, disables both radios, and runs the offline phase. That phase requires no active Android network, creates a checklist edit through the repository's real scheduling callback, and observes ten seconds with zero claim attempts and pending state. It also checks that the connected work request remains enqueued. The coordinator restores network access and invokes verification, which does not request another sync: it waits for the previously scheduled real worker, checks unchanged mutation ID/hash, exactly one claim attempt, successful work completion, and exactly one matching server change/revision. Tokens are test-only and written directly to the encrypted vault; the connection dialog is not tested.

Both instrumentation phases must report `OK (1 test)`. Delivery has a four-minute bound followed by a one-minute work-completion bound; each ADB invocation has a five-minute timeout. Original radio settings are restored in `finally`, even after failure. Existing credentials/data are never cleared or overwritten: use a fresh dedicated installation for another run. If the coordinator is forcibly killed, manually restore its emulator radio settings and cancel any test work before reuse. This covers emulator radio reconnection, not physical packet loss during an HTTP call, Doze, reboot, or production TLS/authentication. Explicit instrumentation launch can restart the app process; this is not a guarantee of delivery while force-stopped or without app execution.

Both phases passed locally on `OfflineSync_Second_API34` / Android 34 against real Kestrel/PostgreSQL. The offline phase reported `OK (1 test)` in 13.280 seconds, including the ten-second zero-claim observation; verification reported `OK (1 test)` in 4.663 seconds. These are observations, not performance targets. Verification checked exactly one claim attempt, unchanged mutation ID/hash, successful real WorkManager completion, and one matching server change at revision one. Wi-Fi and mobile data were restored to their original enabled settings. APK/test assembly passed; no production scheduling constraint, request timeout, or implementation was changed.

### Separate Android Installations

`DeviceConvergenceTests` is an opt-in staged SDK test coordinated by `tools/device-convergence.py`. Start two separate API 26+ Android emulators, the disposable PostgreSQL service, and the interop API above. Build APKs once, then run from the repository root:

```sh
ANDROID_HOME="$HOME/Library/Android/sdk" JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./android/gradlew -p android --no-daemon \
  '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=512m' \
  :sample:assembleDebug :sample:assembleDebugAndroidTest
python3 tools/device-convergence.py emulator-5554 emulator-5556
```

Use dedicated test installations. The coordinator updates debug APKs without clearing data, allocates a fresh workspace per run, and uses isolated encrypted account stores rather than the sample's saved connection. Both emulator disks must remain available for the entire run. Every instrumentation phase must report `OK (1 test)` and a stable replica UUID; the coordinator rejects identical device serials and replica IDs. It stops on failure and bounds each invocation to four minutes. Test account stores remain on the emulator after completion; no existing data is deleted.

The 14 phases seed one installation, bootstrap the other, and make independent edits from the same initial state before uploading either. They verify quantity three, both replica-owned counter components, both tags, completion, and full local CRDT equality with folded server history on each installation. A second round queues a deletion and a concurrent offline rename, then checks that both retain the server's permanent tombstone. Delivery uses the actual SDK runner and HTTP transport, not WorkManager or sample connection UI. Here "offline" means edits without invoking sync, not disabling the device radio. This is not a physical-device, simultaneous-worker, or network-reconnection acceptance test.

All 14 phases passed locally on `Pixel_8` and a fresh `OfflineSync_Second_API34` AVD, both Android 34, against real Kestrel/PostgreSQL. Both emulators ran concurrently, with instrumentation phases deliberately serialized. Each phase reported `OK (1 test)` and both replica identities remained distinct and stable throughout. The two original live-server tests also passed again after extracting their shared signed-token transport helper. APK/test assembly passed. No production implementation, request timeout, or assertion was weakened.

### Live Worker Recovery

`WorkManagerInteropTests` is a separate, opt-in two-phase test. Preparation creates an unsent checklist mutation, claims a 60-second lease, and persists test credentials without scheduling any work. Verification requires a different Android process, checks the unchanged ID/hash and in-flight state, invokes the sample's scheduling hook, and waits for real WorkManager execution. It checks lease recovery, exactly two claim attempts, successful work, and exactly one matching API change/revision. No direct runner or WorkManager test driver delivers the edit.

Use a fresh dedicated test installation. Preparation refuses existing credentials or queue contents; do not clear a real user's app data. It writes the test connection directly to the encrypted vault to isolate worker reconstruction, so it does not test the connection dialog or its claim-binding validation. Both phases must use the same installed APKs, local data, and running PostgreSQL instance. Build and install once, then use ADB instrumentation rather than Gradle connected tests, whose install/uninstall lifecycle can remove the phase state:

```sh
./android/gradlew -p android :sample:assembleDebug :sample:assembleDebugAndroidTest
adb -s emulator-5554 install -r android/sample/build/outputs/apk/debug/sample-debug.apk
adb -s emulator-5554 install -r android/sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r \
  -e class 'dev.offlinesync.sample.WorkManagerInteropTests#persistedEditDeliveredByRealWorkerAfterProcessRestart' \
  -e serverInterop true -e recoveryPhase prepare \
  dev.offlinesync.sample.test/androidx.test.runner.AndroidJUnitRunner
adb -s emulator-5554 shell am force-stop dev.offlinesync.sample
adb -s emulator-5554 shell am instrument -w -r \
  -e class 'dev.offlinesync.sample.WorkManagerInteropTests#persistedEditDeliveredByRealWorkerAfterProcessRestart' \
  -e serverInterop true -e recoveryPhase verify \
  dev.offlinesync.sample.test/androidx.test.runner.AndroidJUnitRunner
```

Both instrumentation commands must report `OK (1 test)`; ADB's shell exit code alone is insufficient. The verification phase has a four-minute bound for delivery and one minute for final worker completion, using Flow observations rather than sleeps. It disconnects after verification. Force-stop requires explicit relaunch: this test does not claim Android automatically runs jobs while an application is force-stopped. It also does not simulate killing an active HTTP call, Doze, reboot, physical network reconnection, or a second device.

Both phases passed locally on Pixel 8 / Android 34 against real Kestrel/PostgreSQL. Preparation reported `OK (1 test)` in 1.656 seconds; verification reported `OK (1 test)` in 47.636 seconds. Verification asserted a different process ID, unchanged mutation ID/hash, recovery from the persisted in-flight lease with exactly two claim attempts, successful real WorkManager run-chain completion, and exactly one server change at revision one. These times are observations, not performance targets. APK/test assembly and sample lint also passed. No production code, timeout, or scheduling constraint was changed for this acceptance test.

Both opt-in tests passed on Pixel 8 / Android 34 against the real Kestrel API and disposable PostgreSQL, with zero skips. This verifies the assertions above using two independent encrypted replicas on one emulator, not two devices. The dedicated host builds and Android lint passes. Earlier local verification includes 34 SDK device tests, four ordinary sample device tests, and nine JVM HTTP tests; those suites were not all rerun during this interoperability slice.

Initial attempts encountered emulator boot/ADB timeouts and cold-server requests exceeding the unchanged client's network timeout under local resource contention. After stopping accumulated Gradle daemons and warming the API, both tests passed using `--no-daemon '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=512m'`. No production timeout or assertion was relaxed. These results establish functional interoperability, not latency or resource-performance acceptance. Wait for emulator boot and the host's post-migration readiness message before running.