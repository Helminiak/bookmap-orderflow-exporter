# Bookmap Exporter — JAR Version & Acceptance Ledger

**Purpose:** One permanent, source-tied record for EVERY distinct JAR candidate deployed or evaluated on Windows Bookmap. Sol reviews code and visual/function evidence, returns specific rework to Qwen, approves accepted engineering candidates, and maintains exact rollback targets. This is the canonical index; the working test and defect details reside in linked GitHub issue/PR comments.

**Scope:** Engineering acceptance for owner-authorized Rithmic simulated live-data and BMF replay. Sol acceptance is **not** owner production release/marketplace approval; it does not authorize orders or funded-account use. Only an owner can authorize those separately.

## Versioning invariant

- Assign a unique test-candidate ID before staging, e.g. `BM-JAR-20261019-001`; increase monotonically for **each different JAR SHA-256**, even when the source SHA is unchanged. Never overwrite a previous JAR or silently reuse an ID.
- Exact **Git source commit SHA, source branch, working-tree cleanliness/diff hash, JDK/Gradle/toolchain, build outputs, full JAR SHA-256, unique artifact filename**, and the specific Bookmap install/load/run identity are recorded. A filename, tag, `git rev-parse HEAD`, or CI success alone cannot establish the loaded JAR.
- Keep immutable original candidate JARs **locally**, with a second trusted backup where practical; record a sanitized retrieval locator/backup verification and SHA-256. Do not commit binary JARs, private machine paths, licensed BMF feeds or raw screenshots to public GitHub without explicit approval.
- Every candidate has its **own GitHub issue/PR checkpoint** identified by unique marker/URL. Creation/staging and final review are separate checkpoints or edits linked from this ledger. GitHub commits preserve revision history; never delete a failed candidate or rewrite its outcome.
- A rollback target must be **independently installed/known-good** or the prior **no-exporter clean baseline**; source code alone is not a working rollback artifact.

## Sol review and rework contract

1. **PRE-BUILD / PRE-DEPLOY REVIEW:** Qwen presents the exact diff, intended behavior and tests. Sol inspects correctness, lifecycle/data safety, GUI usability, typography/layout design, configuration and error-message behavior. Mark `REWORK_REQUIRED` with **exact numbered defects and acceptance tests**, or `REVIEWED_FOR_SIM_NATIVE_TEST` when acceptable to deploy in the authorized simulation environment. Static inspection cannot approve actual runtime visual appearance.
2. **BUILD & STAGE:** Qwen runs clean tests/packaging, builds uniquely named JAR, verifies SHA-256 on Linux and Windows, and posts candidate record in GitHub **before native install**.
3. **NATIVE FUNCTIONAL + VISUAL REVIEW:** Automated Windows helper performs real Bookmap JAR load/configure/Apply/disable/unload, ALL discovered safe exporter controls in static/active/error states, bridge connect/disconnect/reconnect, live simulated archive validation and >100 MiB BMF replay as applicable. Sol compares BEFORE/AFTER screenshots, logs, records and expected behavior. `BUILD_PASS` is **not** `NATIVE_ACCEPTED`.
4. **REWORK:** Sol issues a narrowly scoped, evidence-linked Qwen correction to one defect, with severity, reproduction steps, screenshot/hash refs, affected file/test, expected result, and required RED/GREEN test. Each rebuilt JAR becomes a **NEW CANDIDATE ID**; no in-place mutation of the preceding artifact.
5. **PROMOTION:** Only after tests/evidence for the defined gate pass may Sol label `ENGINEERING_ACCEPTED_SIM_LIVE`, `ENGINEERING_ACCEPTED_BMF`, and optionally `ENGINEERING_ACCEPTED_BRIDGE` individually. `CURRENT_KNOWN_GOOD` only after live acceptance and clean restart/rollback evidence; full end-to-end acceptance requires live+replay+UI+transport where included. Production/release status remains `OWNER_HOLD` unless separately approved.
6. **ROLLBACK:** On native regression or failure, preserve failure evidence, gracefully stop/unload only the candidate exporter, restore the recorded last verified JAR and its settings or restore the clean no-exporter baseline, verify actual Bookmap load/config and one safe smoke, then register `ROLLED_BACK_TO` with exact ID/SHA and reason. Never infer success from staging a prior JAR.

**Required statuses:** `DRAFT`, `REWORK_REQUIRED`, `REVIEWED_FOR_SIM_NATIVE_TEST`, `BUILD_FAILED`, `STAGED_HASH_VERIFIED`, `NATIVE_TESTING`, `NATIVE_REJECTED`, `ENGINEERING_ACCEPTED_SIM_LIVE`, `ENGINEERING_ACCEPTED_BMF`, `ENGINEERING_ACCEPTED_BRIDGE`, `CURRENT_KNOWN_GOOD`, `ROLLED_BACK`, `SUPERSEDED`. Statuses can be combined for individual live/BMF/bridge gates but do not silently upgrade a partially tested candidate.

## Current register (evidence-only; historical snapshots are not live verification)

| Candidate | Source SHA / JAR SHA256 | Evidence | Status | Rollback |
|---|---|---|---|---|
| Historical accepted v0.4 recorder | Source and JAR hash: see [v0.4 validation](2026-10-08-v0.4-runtime-validation.md) | Native 786,347-event capture accepted for v0.4 scope | `HISTORICAL_VALIDATION` (not proof currently loaded) | Original artifact must be located/hashed before selecting as rollback |
| `BM-JAR-20261019-001` v0.4 archive-only baseline | Source `2e0c0c79df9801a68ff9c3f3332a2ffff558ee2d`; JAR SHA-256 `c0f5fce3de8baec75a8282b6373d4b5a80a058fbf3ee40c45dee64d7bbbccd0d` | [Pre-deploy candidate](https://github.com/Helminiak/bookmap-orderflow-exporter/issues/7#issuecomment-6259942230): Gradle pinned-source build PASS, Python 13/13, Java `:test NO-SOURCE`; Windows staged SHA/size verified, native NOT RUN | `REVIEWED_FOR_SIM_NATIVE_TEST` (short archive-only baseline); `NOT_DEPLOYED` | Reverify clean no-exporter Bookmap state; preserve unrelated plugins |
| 2026-10-18 preflight: no exporter loaded | N/A; exact no-exporter Bookmap state in [DR100 checkpoint](https://github.com/Helminiak/dr100-engineering-control/issues/18#issuecomment-6259506438) | Simulated ESZ6 account, no exporter JAR found in inspected Bookmap tree | `CLEAN_BASELINE` (verify current state before any new changes) | Unload candidate; restore current no-exporter state; preserve third-party modules |
| Recovery preview `83af7d4` (historical) | Source `83af7d48d6c53f4c0a50f7eb440e13653878e7e0`; JAR `56d20468607e02811ca0f9278890793e2a2a78b19939d4f0e852808403a7d0cc` | [PR #19](https://github.com/Helminiak/bookmap-orderflow-exporter/pull/19); earlier Windows staging and later owner UI report, no current loaded proof | `HISTORICAL_STAGED`, native acceptance unresolved | Do not use as last-known-good without independent load acceptance |

**First October 19 candidate built, not deployed.** `BM-JAR-20261019-001` records an immutable build and limited static review only. No Windows hash, native SIM load, GUI visual acceptance, live/replay archive, bridge or production readiness is claimed. Assign a new ID to every different subsequent JAR hash.

## BM-JAR-20261019-001 — immutable v0.4 baseline, pre-deploy

- **Parent / source:** historical native-validated v0.4 recorder, clean detached `main@2e0c0c79df9801a68ff9c3f3332a2ffff558ee2d`; no source diff or PR blend. Development branch untouched. Four exporter class payloads, manifest `Manifest-Version: 1.0`, no bridge.
- **Toolchain / tests:** Temurin JDK `17.0.20.1+1`, Gradle `8.10`, Python `3.12.15`. Newer-head command failed because this pin lacks `writeFixtureClasspath` and `smokeTestBundle`; pinned-source `gradle --no-daemon clean test jar` PASS, **zero Java tests (`:test NO-SOURCE`)**; Python validator unittest **13/13 PASS**. This is not Java-regression coverage.
- **Immutable local original:** `bookmap-orderflow-exporter-2e0c0c7-BM-JAR-20261019-001.jar`, 21599 bytes, SHA-256 `c0f5fce3de8baec75a8282b6373d4b5a80a058fbf3ee40c45dee64d7bbbccd0d` in owner-local trusted store `BM-JAR-20261019-001/` (private absolute path withheld). A second clean build had ZIP SHA-256 `c8143f326c3f2e70d632dead3c70d3170e31fb81ef48c022c6f579aac96a9a98` due entry timestamps, but class payloads matched; **never substitute that rebuild for this candidate**.
- **Sol static review:** `REVIEWED_FOR_SIM_NATIVE_TEST` only for a bounded short archive-only baseline; v0.4 `enqueue()` blocks on a full queue (Java 725–740), `stop()` synchronously drains writer (350–382). Watch callback latency and Bookmap responsiveness; no sustained LIVE/bridge/production acceptance. Historical 786347-record capture validates only its previously documented scope.
- **Current gate/rollback:** helper Bookmap opt-in pending interactive Windows UAC. At last positive readback helper was disabled; later helper-status call timed out (state unknown). Windows inert staging SHA/size independently VERIFIED: `C:\DR100\staging\BM-JAR-20261019-001\bookmap-orderflow-exporter-2e0c0c7-BM-JAR-20261019-001.jar`, 21599 bytes, same original SHA-256. Visible SIM, native load, controls/screenshots, live archive, >100 MiB replay, bridge and clean unload **NOT RUN**. Previously inspected clean no-exporter baseline is rollback; reverify before install and preserve unrelated addons. Owner production/release **HOLD**.
- **Issue checkpoints:** pre-deploy https://github.com/Helminiak/bookmap-orderflow-exporter/issues/7#issuecomment-6259942230 ; independent Windows staging hash/readback https://github.com/Helminiak/bookmap-orderflow-exporter/issues/7#issuecomment-6260007535 . Append actual native results and Sol verdict after testing; do not convert staging into native PASS.

## Required per-candidate record (copy into GitHub issue/PR as one linked checkpoint)

    MARKER: BM-JAR-YYYYMMDD-NNN
    CANDIDATE ID / PARENT ID / WHY NEW VERSION:
    SOURCE REPO, BRANCH, FULL SHA / DIFF STATUS / PATCH HASH:
    JDK / GRADLE / JAVA+PYTHON TEST COMMANDS, EXITS, COUNTS:
    BUILD MANIFEST / PACKAGED RUNTIME / JAR FILENAME / SHA256:
    STAGED WINDOWS SHA256 / IMMUTABLE ARTIFACT LOCATOR:
    VERIFIED PREVIOUS INSTALLED JAR OR NO-EXPORTER BASELINE:
    PRE-DEPLOY SOL REVIEW: ACCEPTABLE_FOR_SIM_TEST or REWORK_REQUIRED; comment URL:
    BOOKMAP APP VERSION / TARGET SIM ACCOUNT EVIDENCE / INSTRUMENT:
    ACTUAL LOADED JAR IDENTITY PROOF / GUI BEFORE-AFTER CAPTURE HASHES:
    CONFIG/UI MATRIX: [control, state, expected, observed, verdict, evidence]:
    VISUAL REVIEW: quiet / live-active / connected / warning-invalid / replay:
    BRIDGE RECEIVER CONNECT-DISCONNECT-RECONNECT / UPSTREAM SEPARATE:
    LIVE SIM ARCHIVE / SUMMARY / VALIDATOR / CLEAN STOP:
    BMF INPUT >104857600 bytes / replay actions / repeated normalized extraction:
    DEFECTS (severity, repro, screenshots/logs) / SOL REWORK INSTRUCTION:
    REBUILD NEW CANDIDATE ID / RED-GREEN / FINAL NATIVE RETEST:
    FINAL SOL ENGINEERING VERDICT: SIM_LIVE / BMF / BRIDGE (separate):
    OWNER PRODUCTION/RELEASE APPROVAL: HOLD unless explicitly changed:
    LAST KNOWN GOOD ID / SHA, EXACT ROLLBACK OPERATION AND READBACK:
    GITHUB SOURCE COMMIT / ISSUE OR PR COMMENT URL VERIFIED BY READBACK:

## Routine automation and safety

- Existing Sol+single Qwen; existing SSH/native Windows helper; Hermes learning queue remains paused. No autonomous queue of model workers or new control infrastructure.
- Record every candidate's key identity **before** testing and its true result **after** testing; one concise checkpoint per material JAR candidate, not per screenshot/click. Preserve review and failed candidates for traceable regression analysis.
- A model cannot certify visual behavior from source alone: require actual Windows Bookmap screenshot and effect in static and active modes before the visual verdict.
- Do not claim all buttons tested without a version-bound control inventory; unsupported or hazardous controls must be documented as `SKIPPED` / `BLOCKED` with why.
- After two noninformative attempts or roughly ten minutes stalled, use owner assistance/stop-loss policy rather than repeatedly editing the same cosmetic behavior.

**Task-level acceptance and scenario details:** [DR100 Automated E2E v2](https://github.com/Helminiak/dr100-engineering-control/blob/main/docs/handoff/2026-10-19_BOOKMAP_AUTOMATED_E2E_ACCEPTANCE_V2.md).

## Mandatory production-readiness replay rate/burst and clock evidence (owner expansion)

**Single master execution instruction:** [Sol continuous Bookmap production-readiness prompt](https://github.com/Helminiak/dr100-engineering-control/blob/main/docs/handoff/2026-10-19_BOOKMAP_SOL_MASTER_EXECUTION_PROMPT.md). Every candidate tested for replay must record each supported speed (1x, 2x, 4x, 8x, fastest actually supported; mark unsupported, not PASS); source BMF >104,857,600 bytes, same contiguous market time interval, source hash/identity, unique output, market-time duration vs wall-clock processing duration, per-type event count, event-rate average and rolling peak, producer/writer high-water queue occupancy, peak memory/GC/callback stall, compressed bytes and finalize/flush delays, invalid/overflow range (if any), full gzip and summary validation, and comparative normalized canonical event-tuple ordering/timestamp equivalence across rates. Distinguish Bookmap market timestamp (nanoseconds), monotonic process elapsed time, and UTC log wall-clock time. A rewind/seek begins a new session boundary; it cannot be compared as continuous traffic without that qualification. A faster wall-clock replay must not silently change market event timestamps or MBO order-state chronology. Do not claim throughput headroom, no-loss buffering, or production readiness without measured peak-rate stress and bounded queue-drain recovery. Any Java buffer modification produces a NEW unique JAR/version ledger entry and same-rate RED/GREEN native replay. 
