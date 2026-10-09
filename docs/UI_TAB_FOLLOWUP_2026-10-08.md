> Current correction: initial tab candidate fb77a37 was rejected in Bookmap. See [host/shutdown repair](BOOKMAP_HOST_REPAIR_2026-10-08.md) for reproduced cause, 18 Java tests and corrected candidate; native acceptance remains pending. Earlier implementation/acceptance statements below are historical.

# Configuration / Live status tabs — authorized follow-up

After the nightly notes were pushed at d9da782b052b67b6d151c1198305b22f4cbf29fc and Windows/Ubuntu CI passed, the owner reported that configuration was hidden and requested another tab-layout attempt. This supersedes the earlier restriction to keeping two Bookmap panels; it does not authorize exporter behavior changes.

## Change

Bookmap receives one StrategyPanel titled Orderflow exporter. Plain Swing Configuration and Live status tab bodies share that host. Configuration opens first. Existing controls, labels, values, settings-save/reload listener and measured wrapping network row are retained. Enlarged-font visual inspection also showed the capacity spinner clipping when its FlowLayout wrapped; that row now uses the same measured wrapping layout. Configuration content scrolls vertically; Apply stays outside scrolling. Status HTML scrolls independently; Refresh/Open export folder stay outside scrolling. The status viewport preferred size follows configuration, so expanding diagnostics cannot force a larger host panel. No fixed overall dialog size, absolute positioning, networking, queue, schema, defaults or journal changes.

Runtime source change is limited to UI assembly in BookmapOrderflowExporter.java. Existing BridgeConfigurationLayout.java is unchanged. New ExporterTabsTest.java verifies tab ordering, short-height scrolling, network bounds and action-button visibility at font scales 1/1.25/1.5, plus edited settings saved and one reload. Existing tests remain. Enabled state and status refresh listener behavior are preserved.

## Validation and limits

Local Java build/tests/JAR/Windows bundle: PASS, 15 Java tests (13 existing plus two tab regressions). Public Python: PASS, 15 tests. Linux Swing Configuration top/bottom and long synthetic Live status previews were rendered and inspected at 1/1.25/1.5 font scales. This is not actual Windows Bookmap DPI verification. Current-head Windows/Ubuntu CI status and artifact links are reported in PR #6; native host sizing/tab visibility require the morning check. Nightly benchmark remains on d447a69, not mislabeled as a fresh tab-build performance test.

## Morning check

1. Close Bookmap; retain a copy of the currently installed JAR locally. Extract the new Windows smoke ZIP and replace the exporter JAR, with only one exporter addon installed. Keep BAT/PS1 alongside the new JAR.
2. Open the instrument's addon settings. Expect one Orderflow exporter section with Configuration selected and Live status beside it. Check both tabs are accessible, scroll to Bind/Market port/Health port/capacity and confirm controls do not overlap.
3. Repeat at Windows display scaling 100%, 125% and 150% if available; record actual window/DPI/JAR SHA. Test Save/Apply only when prepared for a new file/session: start a fresh private receiver first, because Apply restarts the exporter.
4. Verify Live status scrolls while Refresh/Open export folder remain visible. Run the existing live smoke only with events flowing. HEALTHY/PASS is transport evidence, not finalized-archive or production acceptance.

Keep draft PR #6 unmerged pending native acceptance and existing release risks. No release number/tag changed. Original nightly closeout remains preserved; this is a separate owner-authorized UI commit.
