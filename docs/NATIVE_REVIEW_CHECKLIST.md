# Native review candidate — not production approved

The visible **FOR REVIEW — NOT PRODUCTION APPROVED** banner identifies this development candidate. Source revision and clean/dirty state are in the JAR manifest; use the review index and SHA256 manifest for the exact artifact. Software/schema defaults are unchanged. No release, merge or Marketplace submission is authorized.

## Preflight: verify Windows-side control and test services

1. **Windows remote-control prerequisite (for Codex-assisted GUI inspection):** Confirm the existing Windows-side **server/control script or service that provides the authorized Windows screen/control session** is actually running and responding **on the Windows Bookmap computer** *before* asking Codex to inspect settings, capture screenshots, or operate the GUI. Record script/service name, its running state, and the time checked in the private test notes. Do not assume a failed screenshot/control command means the Bookmap publisher, TCP bridge, or Java exporter is broken. A stopped Windows control service must be reported as a **remote-control test blocker**; do not bypass it by changing the market-data configuration. This service is **separate from** the optional market-data bridge and from the read-only `Smoke-Test.bat` helper.
2. **Bookmap publisher prerequisite:** Verify the intended exporter JAR is loaded and enabled in a safe replay/test session, the bridge is explicitly enabled only for this test, Bind is the **Windows PC's LAN IPv4 address** (or intentional `0.0.0.0`), and the Java market/health listeners use **TCP 5555/5556**. Verify Windows Firewall permits only the intended Ubuntu peer. The installed publisher is the market-data server; the Windows screenshot/control script is not.
3. **Linux receiver prerequisite:** Start a fresh, compatible Ubuntu receiver **before** the new exporter START; verify it can reach the Windows health listener and subsequently reaches valid START/ACK progression. Receiver `DISCONNECTED` before the exporter starts is not by itself proof of network failure.
4. **Smoke helper prerequisite:** From the Windows computer, run the bundled **`Smoke-Test.bat`** after Bookmap/exporter/receiver are active and events are flowing. This helper **does not run continuously as a server**; it queries health for a bounded interval, logs PASS/FAIL/INCONCLUSIVE, and does not start a second market receiver. A missing/idle exporter may be FAIL or INCONCLUSIVE; do not mistake that for a failure of the Windows remote-control service.
5. If a required script is not running, pause that specific test, start/verify it through the owner's existing authorized Windows workflow, and record what was missing. **Do not automatically restart Bookmap, unload the JAR, reset the feed, change brokerage connections or broaden firewall access** to fix a test-control issue. Preserve exact artifacts/logs and distinguish CI fixtures from physical Windows tests.

## Safe installation and rollback

1. Choose an owner-authorized maintenance/replay window. Do not close Bookmap, replace its loaded JAR or change a broker connection during active trading.
2. Save the current JAR and its SHA256. Keep existing settings. Close Bookmap only in that approved window; remove the old addon registration and install only one candidate exporter JAR. Do not run both builds.
3. Start the Linux receiver with `bash tools/linux/Start-Receiver.sh --host WINDOWS_IP` in the companion receiver installation. Use the Bookmap PC's actual LAN address. Receiver script is not included in this public package. Start a fresh receiver before a fresh bridge-enabled exporter session.
4. Enable the exporter in the safe replay/test installation. Confirm the exact build identity, Configuration/Live status/Information tabs, review banner and receiver notice.
5. To roll back, close Bookmap in a safe window and restore the saved known-good JAR/registration. Do not change schema/config defaults or broker credentials to make a test pass.

## Operator states and recovery

- **DISABLED:** optional bridge is off; local archive validity is separate.
- **WAITING FOR RECEIVER:** no receiver HELLO observed. Start receiver before capacity is exhausted. The notice is persistent across tabs; no modal dialogs or automatic settings changes.
- **CONNECTED / AWAITING ACK:** HELLO observed; ACK progress not yet observed.
- **CONNECTED / ACKED IN RAM:** checkpoint is validation in receiver memory, **not durable storage**. Inspect progress, retention and final summary separately.
- **RECOVERABLE DISCONNECT:** reconnect the same running receiver while retained continuity remains available. Restarting its process is not supported recovery. Finite retention can still exhaust.
- **INVALID / ERROR:** completeness no longer proven. Starting a receiver after exhaustion cannot recover the discarded range. Validate the finalized local archive for offline recovery or begin a fresh session safely. Bind failures require a valid local bind address and unique market/health port pairs.

At 80% retained capacity the notice adds an advisory warning. This is a provisional UI threshold, not a performance budget or a guaranteed time to overflow. Rapid historical catch-up may fill the queue before a one-second UI refresh; callback delivery remains nonwaiting. Opening the exporter panel renders the current warning immediately. The panel is not a system-wide alarm when closed.

For archive-only use, explicitly uncheck **Live Linux bridge** and Apply **in a safe window**. Apply reloads the exporter and creates a new archive/session. The plugin never auto-disables the bridge or restores INVALID to healthy.

## Record actual native evidence

At Windows 100%, 125% and 150% scaling, inspect every tab, top notice, full network fields, Apply and scroll controls. Record screenshots, OS/Bookmap/Java versions, artifact/source hashes and actual display scaling. Local Swing font-scale rendering is preliminary only.

Exercise missing receiver, HELLO before ACK, working ACK progression, brief disconnect/reconnect, sustained/stalled receiver, retention warning/exhaustion, bind error, disable/new session, repeated start/stop and rollback. Validate archive CRC, sequence, lifecycle and summary independently of bridge status. Complete accelerated HISTORY→REALTIME, representative REALTIME and full-session soak. Measure matched archive/bridge-off/on callbacks/UI/CPU/memory/storage. Keep acquisition, journal, transport, receiver persistence, UI and release acceptance separate.

Report observed versus expected behavior with exact artifact SHA and safe reproduction. Do not upload raw licensed captures, credentials or account details to public issues. Use sanitized diagnostics. Current native/release gates remain pending.
