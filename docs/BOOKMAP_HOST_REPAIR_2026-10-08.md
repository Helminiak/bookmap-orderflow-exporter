# Blank Configuration and disable-stall repair — 2026-10-08

The owner rejected fb77a37 in actual Bookmap: Configuration was blank and disabling the addon stalled. That candidate is not runtime-approved and must not be recommended as a working Bookmap UI. Prior Linux tab-content previews/green CI did not cover Bookmap host geometry.

Runtime repair source anchor: `d7022e88740bc705b67c32e96327861c4bf93774`. A subsequent packaging-note commit corrects the Windows bundle README to describe tabs; final delivered SHA is recorded in PR #6 and ORDERFLOW-LATEST-BUILD.txt.

## Reproduced cause and correction

Read-only inspection of installed Bookmap's panel wrapper showed horizontal-only GridBag fill for plugin panels (weighty zero), with a weighted vertical filler. The old tabs explicitly allowed zero minimum size while their scrolling contents requested the entire form's preferred size. A constrained host falls back to minimum sizes and collapses Configuration's viewport. The new regression recreates those host constraints using the actual StrategyPanel factory at font scales 1/1.25/1.5 and initially failed with the old sizing.

Fields now request a scrollable preferred viewport of up to 12 font-height lines, with width supplied by the host. Tabs retain minimum room for headers/buttons plus six font-height lines of content. Sizes derive from fonts/layout managers, not absolute positioning or a larger entire dialog. Network/capacity measured wrapping is retained. Tabs/scrollbars remain navigable with addon disabled; settings editing/Apply remain disabled. Panel construction enables our children directly, avoiding StrategyPanel's internal recursive helper dependency (the pinned API test class referenced a Bookmap-only GUI class absent from the test classpath). Configuration remains first, Live status second.

The STOP path previously called historical bridge offer, which could wait ten seconds when retention was full and no receiver ACKed it. Lifecycle STOP now uses the existing non-waiting bridge offer; full retention explicitly invalidates the bridge while the journal remains independent. Ordinary HISTORY acquisition still uses its original backpressure. Bridge close now runs in finally even if summary writing or other stop work fails. Clean writer finalization remains synchronous; writer queue/join waits and the bridge's bounded close grace can still take time. This does not promise instantaneous shutdown or disk-stall recovery.

## Verification and limits

Public Java suite: 18 tests (15 previous, one host-layout regression, two shutdown regressions). Public Python: 15 tests. Host regression uses real StrategyPanel factory and horizontal-only host constraints, confirms visible viewport and disabled-addon navigation. Existing font-scale/network/control/save/reload coverage retained. Shutdown tests: missing receiver with saturated one-event retention stops within three seconds, journal includes STOP, both ports can rebind; deterministic summary IOException preserves error and still releases both ports. Six private generic Java/Python bridge integration tests passed against the repaired runtime in 19.24s. No private strategy code or market captures are included.

Linux Swing host previews at font scales 1/1.25/1.5 rendered and inspected. This reproduces host constraints, not an actual Windows Bookmap window. Windows/Ubuntu current-head CI and exact final SHA are recorded in PR #6. Actual corrected Bookmap visibility/disable behavior still needs owner verification; all broader load/threading/storage/security risks remain open. The earlier failing API-classpath and host tests were addressed, not hidden.

## Replacement and owner check

After successful Windows/Linux CI, download the newly built Windows bundle and replace only the exporter/smoke files previously placed directly in Windows Downloads. Remove the obsolete fb77a37 ZIP; retain unrelated downloads and all installed/configured data. The JAR remains v0.5 candidate; ORDERFLOW-LATEST-BUILD.txt and the uniquely named host-fix ZIP identify its commit. Do not merge or tag it from CI alone.

Close Bookmap before loading/replacing its installed JAR. Load only one exporter addon. Verify Configuration contains controls and can scroll, Live status opens, Bind/ports/capacity are usable, and disable succeeds under ordinary conditions. If bridge is enabled, start a fresh receiver before starting the publisher session. Report any remaining failure with installed build identity and Bookmap logs/screenshot. Preserve invalid archive evidence rather than approving it as complete.
