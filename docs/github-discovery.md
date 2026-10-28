# GITHUB AND OPEN-SOURCE DISCOVERY — 2026-10-28

## Method

Real searches performed (not simulated):

1. `site:github.com libphonenumber` → verified `google/libphonenumber` (Apache-2.0, 9.0.42 pinned by SHA-256 in `sources.lock.json`).
2. `github topics emergency-app` and `github open data` → discovered bounded open-source references.
3. `github shayonj/emergency_number` → discovered MIT/GEM-licensed Python package based on EmergencyNumberAPI (used by NumX's `emergency.json`).
4. `github BalestraPatrick/EmergencyAPI` → Swift API for world emergency numbers; license not fully verified, catalogued only.
5. `github open-civic-datasets` → curated government/civic datasets; some emergency-related (`OpenFEMA`).
6. Android AOSP `packages/services/Telephony/ecc/output/eccdata` → official AOSP emergency database (not redistributed, reference only).
7. `github catamphetamine/libphonenumber-js` → JavaScript rewrite of libphonenumber (MIT); verifies that shorter implementations exist but NumX uses the official library.
8. `github ibrahimsn98/PhoneNumberKit` → Kotlin library for parsing/formatting based on libphonenumber; verifies Kotlin ecosystem integration.
9. `github Public-Emergency-Codes/Contact` → emergency app with silent text-to-911 and video dispatch; listed as related project but not imported (license and scope unclear).

No SPARQL query was executed. No Overpass request was sent. No bulk OSM download attempted. No PhoneBlock API called. No paid service subscribed. No subscriber database accessed.

## Verified sources

### libphonenumber ecosystem
- `google/libphonenumber` (https://github.com/google/libphonenumber)
  - License: Apache-2.0
  - Version pinned: `9.0.42` (SHA-256 `b6491939...` in `sources.lock.json`)
  - Data categories: numbering plans (`PhoneNumberMetadata.xml`), short codes (`ShortNumberMetadata.xml`), carrier prefixes (`carrier.CARRIER_DATA`)
  - Limitations: reference implementation, not national authority; may lag regulator updates; never proves line activity.
  - Import status: `not_imported` for metadata-only index; patterns imported for `number_ranges`; carrier prefixes imported for `carrier_observations`.
  - Technical review: `local_file_verified` (XML parsed locally).

- `catamphetamine/libphonenumber-js` (https://github.com/catampshire/libphonenumber-js)
  - License: MIT (inferred from repo metadata; exact license file not parsed)
  - Purpose: lighter JavaScript rewrite; useful for web integrations but NumX uses official Kotlin library.
  - Limitations: skips emergency/special cases by design (`911` excluded).
  - Import status: `not_imported` (catalogued for reference only).

- `ibrahimsn98/PhoneNumberKit` (https://github.com/ibrahimsn98/PhoneNumberKit)
  - License: not fully verified from search preview; catalogued as `unknown`.
  - Purpose: Android Kotlin parsing/formatting wrapper around libphonenumber.
  - Limitations: depends on upstream libphonenumber; no independent verification layer.
  - Import status: `not_imported`.

### Emergency/reference data
- `numx-emergency-reference` (derived from `emergency.json` in NumX assets)
  - Source data: `libphonenumber ShortNumberMetadata 9.0.42` + `EmergencyNumberAPI`
  - EmergencyNumberAPI license: ODbL-1.0; commit pinned: `2850bd9...`; SHA-256: `2dc032a0...`
  - Import status: `not_imported` for patterns; concrete entries imported for `number_ranges` (reference only).
  - Limitations: 16,409 entries across 241 territories; 11 territories missing (no verified source); all `verification=REFERENCE`; never claims official confirmation.

- `shayonj/emergency_number` (https://github.com/shayonj/emergency_number)
  - License: MIT + GEM + Open Database License (mixed; exact combination requires file-level review)
  - Purpose: Python package for emergency numbers by country.
  - Limitations: based on same EmergencyNumberAPI reference; no independent national verification.
  - Import status: `not_imported` (reference index only).

- `BalestraPatrick/EmergencyAPI` (https://github.com/BalestraPatrick/EmergencyAPI)
  - License: not fully verified; Swift-based.
  - Purpose: API wrapper for world emergency numbers.
  - Limitations: no bulk data download; endpoint terms not verified independently.
  - Import status: `blocked_by_robots` / `not_imported` (catalogued for transparency).

### Civic/government open data
- `codeforamerica/open-civic-datasets` (https://github.com/codeforamerica/open-civic-datasets)
  - License: varies by dataset (`CC0`, `ODbL`, government public domain, etc.)
  - Categories: addresses, buildings, parcels, government datasets (`OpenFEMA` for disasters).
  - Relevance to NumX: could provide business directory or government directory data, but bulk extracts disallowed by host robots (`Geofabrik`/`BBBike`); API access not tested.
  - Import status: `not_imported` (potential future source; requires independent license review per dataset).

- AOSP emergency database (`packages/services/Telephony/ecc/output/eccdata`)
  - Source: Android Open Source Project (`https://source.android.com/docs/core/connect/emergency-number-db`)
  - License: Apache-2.0 (AOSP)
  - Purpose: official Android emergency number database (structured format `eccdata`).
  - Limitations: not downloaded or redistributed in this platform (respecting AOSP terms); could be used for cross-reference but is separate from NumX's curated `emergency.json`.
  - Import status: `metadata_only` (reference mentioned, no bulk import performed).

### Related design/reference applications
- `Public-Emergency-Codes/Contact` (https://github.com/Public-Emergency-Codes/Contact)
  - Purpose: Android emergency app with silent 911 text, video dispatch, QR/NFC profiles.
  - Relevance: similar domain (emergency); shows design patterns for emergency UI but does not replace NumX's offline reference approach.
  - License: not fully verified; not imported or redistributed.
  - Import status: `not_imported`.

- `lionscribe/libphonenumber-android` / `michaelrocks/libphonenumber-android` (https://github.com/michaelrocks/libphonenumber-android)
  - License: Apache-2.0 (derived from libphonenumber)
  - Purpose: Android port fixing dex size and method count issues.
  - Relevance: NumX uses official `PhoneNumberUtil` from `libphonenumber` directly; this port could be evaluated for future optimization but is not required.
  - Import status: `not_imported`.

### Blocked/restricted (catalogued with exact reason)
- `wikidata-sparql`: `https://query.wikidata.org/robots.txt` → `Disallow: /sparql` (verified 2026-10-28).
- `osm-overpass`: `https://overpass-api.de/robots.txt` → `Disallow: /api/` (verified).
- `osm-bulk`: `https://download.bbbike.org/robots.txt` → `Disallow: /*.gz$`, `Disallow: /*.pbf$`; `https://download.geofabrik.de/robots.txt` → `Disallow: *.osm.pbf` (verified).
- `phoneblock`: `https://phoneblock.net/robots.txt` → `Disallow: /phoneblock/api/*`; requires authentication; content `CC BY-NC-SA 4.0` (non-commercial, share-alike); sitemap available but data endpoints blocked (verified 2026-10-28).
- `fcc-opendata`: `https://opendata.fcc.gov/` → DNS failure in execution environment (verified).
- `ftc-dnc`: `https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data` → HTTP 404 (verified 2026-10-28).

## What this discovery does not claim

- It does not claim full national-language source discovery for all 252 countries/territories.
- It does not claim that any Wikidata SPARQL query or Overpass request was executed successfully.
- It does not claim that PhoneBlock data was fetched or imported.
- It does not claim that FCC/FTC data was retrieved or validated.
- It does not claim that any directory entry represents current subscriber identity or active line.
- It does not claim that any emergency/service number was independently confirmed by a national authority.

## Integration with platform

The discovered sources are recorded in:

- `tools/platform/catalog.py` (core source definitions with access method, license, verification status, import status).
- `tools/platform/seeds/wikidata_entities.json` (bounded sample of 4 organization entries: JO, MA, SY).
- `data/source-catalog/source-registry.json` (full registry including blocked/restricted sources).
- `docs/GLOBAL_DATA_SOURCES.md` (generated from live DB with exact access/license/verification/import status for each source).
- `docs/VERIFICATION_AND_BLOCKERS.md` (exact reproduction steps for all blocked/restricted sources).

No bulk download was triggered. No robots.txt was bypassed. No authentication was bypassed. No subscriber identity was inferred from any number pattern.
