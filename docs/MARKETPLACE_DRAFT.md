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
