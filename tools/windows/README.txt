ORDERFLOW v0.5 - BOOKMAP HOST LAYOUT / STOP REPAIR CANDIDATE

1. Extract this ZIP into a normal folder. Keep all files together.
2. Close Bookmap and replace its previous exporter JAR with the JAR here.
   Keep one exporter addon installed. Restart Bookmap.
3. Open the single Orderflow exporter panel. Configuration opens first;
   Live status is the second tab. Scroll Configuration to see all settings.
   Apply stays visible below the settings. Tabs/scrolling work even when
   the addon is disabled; editing and Apply are disabled until it is enabled.
   This repairs the initial blank-tab candidate; actual Bookmap acceptance
   is still pending. Check visibility and normal disable behavior before approval.
4. On Ubuntu start the existing private receiver, using your Windows LAN IP:
   python -m orderflow_bridge.receiver --host WINDOWS_LAN_IP --port 5555 --health-port 5556
5. Enable the bridge in Bookmap and Apply/restart for a fresh START.
   If the stream is INVALID: restart Ubuntu's receiver first, then restart
   the exporter. Keep responsive live journal enabled.
6. Do not double-click the PS1 helper directly. Double-click Smoke-Test.bat ON THE WINDOWS BOOKMAP MACHINE. Press Enter
   after Ubuntu shows HEALTHY and Bookmap is receiving/replaying events.

Java 17+ must be available. The launcher automatically checks Bookmap runtimes,
JAVA_HOME, PATH and common Java installation folders, skipping older Java.
If not found, the launcher asks for the full
path to java.exe (an appropriate Bookmap runtime or installed Java).
No Windows Python installation or administrator access is needed.
The script never changes your firewall or registers another market receiver.

The BAT prints Windows LAN IP(s) and finds the health listener on LAN/localhost.
Defaults: discovers a local health listener on port 5556; checks for 60 seconds.
If you bind Bookmap to a specific LAN interface instead of 0.0.0.0:
  Smoke-Test.bat -HostName YOUR_WINDOWS_LAN_IP
For a custom health port:
  Smoke-Test.bat -HealthPort 5566
For a specific Java runtime:
  Smoke-Test.bat -JavaPath "C:\path\to\java.exe"

PASS = receiver ACKs advanced, connection remained healthy, no bridge/journal
       overflows or writer failure, and no observed ACK stall with pending events.
FAIL = unhealthy/missing replies, invalid stream, overflow, stalled ACKs,
       backwards counters or a changed exporter session.
INCONCLUSIVE = too few/no new events. Rerun with market/replay events flowing.
A timestamped smoke-test-*.json diagnostic report is saved beside the launcher.

Also check Ubuntu says HEALTHY with zero gaps/duplicates; the health channel
identifies the registered receiver but does not prove which physical host it is.
This smoke test does not measure Bookmap slowdown or validate the final gzip
archive. Finalize/validate that journal separately after stopping cleanly.
