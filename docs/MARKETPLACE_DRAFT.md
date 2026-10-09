# Bookmap Marketplace listing draft

Draft only. No vendor submission, publication, release approval or business terms are implied.

## Proposed original listing

**Name:** Orderflow Raw Exporter

**Description:** Capture Bookmap MBO and trade callbacks as sequential, compressed NDJSON event archives with clean-stop summaries. Inspect export health from a dedicated Live status tab. Optionally send the same event stream to a Linux receiver over a trusted local network. Configuration and Information pages provide export controls, setup guidance and support links.

**Included:** One addon JAR, Windows smoke-test launcher, installation instructions and a repository-provided public canonical archive validator. The Linux bridge receiver is currently a separate private project; do not advertise it as a public download or include private research code in the listing.

**Requirements:** Bookmap with compatible MBO/trade API and provider entitlement. Built against API 7.6.0.20, Java 17+. Exact supported Bookmap builds/providers must be established through acceptance testing before listing. Captures reflect supplied callbacks; completeness and initial-book semantics need separate evidence.

**Data handling:** Archives stay in the chosen local folder. Optional bridge transport is for trusted LAN use and has no authentication/TLS. Market data remains subject to the user's provider rights. No trading signals, model, execution functionality or profitability claim is included.

**Help/support:** Repository installation documentation and GitHub issues. Use original screenshots of the accepted exporter build; no third-party addon screenshots, logos or copied descriptions.

## Steps before submission

1. Complete actual Bookmap Windows UI/display-scaling, clean shutdown and sustained-load acceptance; approve an exact build identity.
2. Owner decides license/distribution rights, vendor identity, support contact, product price/free status and whether the private receiver will be distributed. These terms have not been selected.
3. Prepare original screenshots, supported-build matrix, installation/uninstallation guide, changelog and final downloadable artifact. Keep private strategies and licensed captures excluded.
4. Use the owner's Bookmap account: My Account → Become a Vendor → product listing. Bookmap reviews and approves listings before publication. Account access and owner-approved final listing are required for submission.

Source: [Bookmap's vendor process](https://bookmap.com/en/b2b/bookmap-marketplace), checked 2026-10-08. Its documented listing fields include description, price, subscription interval and a dedicated license field. Confirm the current free-addon/distribution procedure with Bookmap rather than inventing licensing terms.

## Verified references and unresolved commercial gates — 2026-10-08

The [official vendor page](https://bookmap.com/en/b2b/bookmap-marketplace) describes vendor registration through My Account, a product page, price/subscription/license field setup, and Bookmap review before publication. These are published onboarding steps, not evidence that this candidate has been accepted. Fee schedule, seller agreement, free-addon procedure, source disclosure and exact submission package requirements remain questions for Bookmap; no agreement or contact was made.

The [official API guide](https://github.com/BookmapAPI/DemoStrategies) documents compile-only API artifacts tied to a Bookmap version/build, API-version compatibility annotations and `Layer1StrategyDateLicensed` entitlement checking. Its license field must first be obtained from Bookmap. It also documents provider data-access limitations. Our pinned API 7.6.0.20/JDK17 build passing is not a certification of every customer Bookmap build. Test exact builds and provider capabilities; do not insert a guessed license field or invent a payment system.

Our engineering gates are stricter than a product listing: exact clean continuity/summary counts, recovery within declared retention, measured resource budgets, safe failure handling, sustained REALTIME/soak, native scaling, reproducible packages and independent review. These remain pending where the readiness register says so.

Questions prepared for owner-approved Bookmap contact: which current releases/runtime combinations must be supported; what listing/package review is required for an acquisition exporter; which license field and entitlement APIs apply; who distributes the separate receiver and supports updates; what fees/agreement terms apply; and what provider/export restrictions Bookmap expects vendors to document. Feed/export/redistribution rights require the actual applicable agreements; API access does not answer that question.

## Customer value and credible alternatives

Value proposition to validate: provide a supported acquisition contract, independent archive validator, explicit delivery uncertainty, predictable retention, repeatable installation and useful diagnostics, saving customers the work of building and supporting those pieces. Measure setup/support time and recovery outcomes before making quantified savings claims.

| Alternative | Published capability | Our proposed value / remaining proof |
| --- | --- | --- |
| [Bookmap L1 Java demos](https://github.com/BookmapAPI/DemoStrategies) | Developer starting point for custom modules | Packaged exporter, validator and diagnostics; customer compatibility/support acceptance pending |
| [Bookmap Python API](https://github.com/BookmapAPI/python-api) | Provider-dependent MBO subscription and live callbacks including initial snapshot | Unified Java acquisition and archive lifecycle; historical capability differs and must be verified per source |
| [Bookmap recording/replay](https://bookmap.com/knowledgebase/docs/KB-SettingUpAndOperating-HeatmapSupportingFeatures) | Save live session data for later Bookmap replay | Decode once into canonical archive and downstream compiled research storage; no claim to replace Bookmap replay or licensed feed access |

Third-party dependency notices are packaged for JeroMQ/jnacl. Verify their exact pinned license files and delivery obligations in the final artifact. Do not promise profitable trading or complete upstream exchange data.
