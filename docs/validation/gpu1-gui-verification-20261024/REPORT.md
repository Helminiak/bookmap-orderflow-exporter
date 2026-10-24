# GPU 1 verification — v0.5a GUI scroll/focus defect (DR100)

Verdict: PASS — the existing correction is sufficient for the reported defect. No source change made.

## Verified state
- Separate verification worktree on branch `gpu1-gui-verify-20261024`, created from the
  origin/feature/linux-live-bridge tip; working tree clean at verification time (no patch produced).
- Exact verified source commit: `8a06c39af78bf2a27fc7a517518f47d98555ada9`
  ("Fix DR100 replay journal overload and hidden status refresh").

## Source audit
The correction is present in this checkout:
- `BookmapOrderflowExporter.java:950-955` — the periodic refresh Runnable returns early when
  `statusTabs == null` or `tabs.getSelectedIndex() != 1`, before any label mutation.
- `BookmapOrderflowExporter.java:838-841` — selecting Live status triggers one immediate refresh.
- The unconditional construction-time `refreshStatusLabel()` call from baseline 58ea521 was removed.

Exhaustive grep of src/main/java for `.setText(` / `revalidate` / `repaint(` / `requestFocus` /
`setEnabled(`: line 772 (user-initiated file chooser), line 822 (static preview panel only),
line 899 (`setEnabledRecursively`, enable/disable lifecycle transition only, not periodic), and
line 1065 `label.setText()` — the ONLY periodic UI mutation, guarded at line 955. No requestFocus
anywhere in main source: refreshes cannot force focus or navigation changes.

Conclusion: while Configuration (or Information) is selected, a periodic status refresh performs
zero UI mutations, so no layout invalidation reaches Bookmap's outer host and the page scroll
position and keyboard focus owner are untouched. Apply remains reachable.

## Test evidence
Toolchain: Gradle 8.10 `--no-daemon`, JDK 25 runtime.

Focused GUI/layout suites (all pass):
- ConfigurationRefreshTest tests=1 failures=0 errors=0 skipped=0 — enabled tabs, scrolled host at
  y>0, selected text + explicit focus owner; 50 forced refreshes: zero hidden text changes, scroll
  position unchanged, tab index retained, focus owner and selection range intact, fresh diagnostics
  on Live status entry.
- ExporterTabsTest tests=3 failures=0 errors=0 skipped=0 — outer-scroll reachability of every field
  + Apply at widths 400/560/800, font scales 1/1.25/1.5; Apply saves edited settings and reloads once.
- BridgeConfigurationLayoutTest tests=2 failures=0 errors=0 skipped=0.

Full regression suite (integration evidence with GPU 0's journal repair):
`gradle --no-daemon test`: BUILD SUCCESSFUL, classes=11 tests=38 failures=0 errors=0 skipped=0.
Class list matches all @Test-bearing sources exactly; nothing silently up-to-date-skipped.

Raw JUnit XML for all 11 classes: `test-results/` in this directory.

## Residual observations (no patch, per assignment step 5)
- While Live status IS selected, refreshes still setText every ~250 ms; the status panel's fixed
  viewport keeps its preferred size constant, so outer-page geometry is stable except if a very long
  unwrapped line makes the inner horizontal scrollbar appear (~17 px height change while viewing
  Live status). This does not affect the Configuration page or Apply reachability.
- The installed Windows v0.5a preview (`bookmap-orderflow-exporter-BRIDGE-RECOVERY-PREVIEW-83af7d4.jar`)
  is still the old failing build. Passing source tests do NOT mean Windows has been repaired; a new
  candidate JAR must be built and owner-installed for visual/DPI acceptance (out of scope here: no
  deploy, no installed-JAR modification, no Windows Bookmap operation).

## Scope compliance
No installed Bookmap JAR touched, no Windows GUI operated, no Hermes configuration changed, nothing
deployed. GPU 0's worktree and branch untouched; this verification branches from the same origin tip,
so integration with GPU 0's journal repair is a no-op merge (identical base commit).
