# DR100 journal intake and hidden-status refresh repair

Scope: offline source/tests on PR #6 baseline `58ea521c7cdc9c5b34734adb4b60eb623a935135`.
Installed failing Windows preview is owner-identified as
`bookmap-orderflow-exporter-BRIDGE-RECOVERY-PREVIEW-83af7d4.jar`.
The preview and baseline use the same `realtimePhase && responsiveLiveJournal`
intake predicate and unconditional hidden HTML status updates. Later PR #6 storage,
shutdown and stall fixes are retained. No installed JAR, Bookmap process, helper,
gateway, worker, broker connection or licensed input was modified.

## Evidence and diagnosis

[Control evidence](https://github.com/Helminiak/dr100-engineering-control/issues/18#issuecomment-6379174262)
was read directly. Both original gzip archives were read to EOF and validated with
their original summaries, without modification:

| Capture suffix | SHA256 | Result |
| --- | --- | --- |
| `030416_588` | `21b7e688b1a64c0363911fca49cefdc3eb81c3fb02deddb0b2964364032eb427` | 1,890,049 records, zero gaps, validator exit 0 |
| `030424_446` | `4784cd2162cbc5060230ea1e3c4a927524aa7296b0920523eb725905d3627493` | 1,890,385 records, zero gaps in retained prefix, validator exit 2, missing STOP |

Good capture: 1,889,802 HISTORY records; REALTIME_START at seq 1,889,803;
STOP at 1,890,049. Failed capture: 1,890,281 HISTORY records;
REALTIME_START at seq 1,890,282, followed by 103 retained market records and no
STOP. Its summary reports a 1,000,000-slot journal high water, one overflow and
26,120 dropped records, exactly allocated minus persisted; bridge/error are null.
The gzip trailer passes on both. This confirms a journal failure after Bookmap
reported REALTIME, not a bridge fault. The retained prefix cannot identify the
exact callback at which the later overflow or peak rate occurred.

The historical preview did not record the global source flag. Thus archives
prove callback phase, while the owner's session report supplies replay context;
they cannot retrospectively prove `RunModeHelper.isLive()` for that old process.

## Minimal behavior change

Pinned API 7.6.0.20 exposes `RunModeHelper.isLive()`. Its
[official Javadoc](https://javadoc.bookmap.com/maven2/releases/com/bookmap/api/api-core/7.6.0.20/velox/api/layer1/common/RunModeHelper.html)
specifies false for data replayed by Bookmap; true means provider data (which may
itself be delayed/replayed). The local API bytecode/signatures and that documentation
were inspected. Capture this nullable flag at initialization:

- Explicit Bookmap replay: keep existing strict journal backpressure during both
  HISTORY and REALTIME callback phases, including STOP. The existing 30-second
  zero-progress writer deadline remains; failure stays invalid and partial.
- Provider or unknown source: retain existing responsive REALTIME nonwaiting
  offers and explicit overflow invalidation. No timestamp/rate heuristic can
  accidentally turn a live callback into a blocking replay callback.
- Preserve event phase, sequence, timestamp, IDs, filters, queue size, bridge
  behavior and settings defaults. Add `bookmap_source_mode` to summary and Source
  to diagnostics so the next isolated native capture can attest the decision.

This does not promise lossless provider replay, sustained throughput beyond the
writer, a bounded overall shutdown under all storage faults, or repaired old
archives. A progressing replay may wait intentionally; responsive provider LIVE
still fails closed under overload.

The separate UI change skips HTML status mutation unless Live status is selected,
checking on the EDT; selecting it refreshes immediately. Configuration/Information
therefore do not receive status-driven layout invalidations. No panel rebuild,
settings reload, focus request or scrollbar restoration loop is introduced.

## Reproduction and checks

`ReplayJournalTest` fills the minimum production queue with undrained HISTORY,
then calls REALTIME_START and attempts more callbacks. `ConfigurationRefreshTest`
uses enabled tabs, a scrolled host, selected text and an explicit Swing focus owner,
then schedules 50 forced refreshes. Against unmodified production source at the
baseline, the two replay tests and UI test fail; provider/unknown-source overload
control passes. With the patch all four pass. The UI test waits for initial Swing
layout before measuring; it checks hidden text-change suppression, scroll, focus,
selection/edit state and fresh diagnostics on tab entry. This is Swing regression
evidence, not Windows Bookmap visual/DPI or OS focus acceptance.

`ReplayBurstFixture` runs offline with explicit replay mode, bridge disabled,
1,000,000 slots and 1,916,505 total records. A gated writer retains history until
the queue is exactly full at REALTIME_START. The next callback demonstrably waits;
releasing the writer drains all events, writes STOP and finalizes matching summary.
Synthetic MBO adds/cancels are paired; validation reads the complete gzip and checks
sequence/lifecycle/anomalies and summary. The fixture uses the injectable storage
boundary, not a Bookmap process or native storage-durability certification.

Commands (JDK17 / Gradle8.10 / Python3.12):

```sh
gradle --no-daemon test --tests '*ReplayJournalTest' --tests '*ConfigurationRefreshTest'
gradle --no-daemon clean test jar writeFixtureClasspath smokeTestBundle
python -m unittest discover -s tests -v
java -Xmx2g -cp "$(cat build/fixture-classpath.txt)" \
  com.limacharlie.orderflow.ReplayBurstFixture SYNTHETIC_OUTPUT_DIR
python tools/validate_export.py SYNTHETIC_CAPTURE.ndjson.gz \
  --summary SYNTHETIC_CAPTURE.summary.json
```

Local full build: 38 Java tests, zero failures/errors/skips; 15 Python tests PASS.
The synthetic history feed measured approximately 2.7m callbacks/second on the
first run. Rate is machine/fixture-specific; queue occupancy and lossless final
validation are the acceptance evidence. Windows CI and actual Bookmap replay,
loaded-artifact/source flag verification and native scrolling/focus remain pending.
See the delivered receipt bundle for exact commits, commands, logs and hashes;
this document does not assert its own future commit hash or CI state.

## Installation and rollback plan — not executed

1. Keep this candidate offline until exact-head review and Windows/Ubuntu CI are
   checked. Assign a fresh candidate identity using the source commit and artifact
   SHA256; do not reuse preview, 004 or 005 identities. Existing build filename
   `v0.5d` alone does not establish provenance; verify the manifest source SHA and
   `Orderflow-Working-Tree-Dirty: false` as well as the receipt hash.
2. At a separately authorized maintenance window, establish fresh no-live-order-route
   evidence and an isolated Bookmap file replay context before any GUI interaction.
   Close Bookmap; preserve/hash the installed JAR and saved configuration as evidence.
   Keep the failed preview quarantined. Preserve BMF and both original captures.
3. Stage only the reviewed/hash-matched candidate under a unique name and load one
   exporter. First use bridge-off replay. Confirm diagnostics/summary report
   BOOKMAP_REPLAY even after REALTIME_START. Repeat the same BMF attachment/burst
   scenario; require STOP, matching allocated/persisted counts, zero journal drops,
   no overflow, complete gzip read and validator exit 0 with original summary.
   Confirm scrolling, typing, Tab focus and Apply remain usable while enabled at
   the owner's display scale. Apply restarts capture; preserve every output.
4. On missing source-mode evidence, invalid capture, stall, UI regression or any
   live-route uncertainty, stop acceptance. Do not weaken validator checks or raise
   capacity as a substitute. Roll back to a closed/no-exporter baseline by removing
   only the candidate from the add-on configuration. Preserve the candidate, logs,
   partial output and original backup. Restoring the known-failing preview is not
   an automatic safe rollback. No production deployment, trading, merge or release
   is authorized by these offline results.
