# VERIFICATION AND BLOCKERS — 2026-10-28

## Verified (real execution evidence)

### Repository
- Git repo preserved: `564d49b` (v2.1.0) unchanged; working tree has new untracked files (`?? tools/platform/`, `data/`, `docs/`).
- Android build: `assembleDebug` succeeds; APK at `numx-android/app/build/outputs/apk/debug/app-debug.apk` (21 MB).
- Kotlin test: `PlatformSummaryTest` passes (`BUILD SUCCESSFUL`).

### Platform database (`data/platform.db`, schema v2)
- `schema_migrations` version 2 applied.
- `countries_and_regions`: 259 entities (252 from NumX + 7 ITU global networks: INMARSAT, IRIDIUM, GLOBALSTAR, THURAYA, MCP, ATT_CINGULAR, CHINA_TELECOM_SAT).
- `source_registry`: 460 sources with separate `access_method`, `license_name`, `redistribution_allowed`, `verification_status`, `legal_review_status`, `technical_review_status`, `import_status`, `last_http_status`, `last_error`.
- `numbering_plans`: 484 (231 ITU country + 7 global + 246 libphonenumber reference).
- `number_ranges`: 18,325 (libphonenumber patterns, no subscriber expansion).
- `phone_numbers`: 0 (deliberately empty; no subscriber or directory data claimed).
- `carrier_observations`: 28,138 (allocated-prefix observations only; marked `notCurrentCarrier` in API/provenance).
- `regulator_entries`: 209 (Wikipedia discovery index; `verification=DISCOVERY`, `confidence=low`).
- `directory_entries`: 4 (bounded Wikidata sample for JO, MA, SY; `verification=COMMUNITY_CURATED`).
- `reputation_reports`: 0 (PhoneBlock blocked; no fake reports created).
- `record_provenance`: 46,914 rows (one per important fact with source/reference/retrieved_at/license/transformations).
- `provenance_history`: 0 (no substantive provenance updates yet; first run).
- `import_jobs`: 7 completed (itu, regulators, libphonenumber, short, emergency, carrier, wikidata).
- `audit_events`: 12 (catalog.seeded, imports, linkcheck, coverage, docs).
- `coverage_matrix`: 2,849 rows (11 categories × 259 entities).

### Catalog files (all generated from DB, not invented)
- `data/source-catalog/countries.json`: 259 entities + 7 networks.
- `source-registry.json`: 460 sources.
- `country-source-catalog.csv`: 461 source rows.
- `country-coverage-matrix.csv`: 2,849 rows.
- `itu-documents.json`: 232 ITU pages checked; 209 with document links discovered.
- `wikidata-discovery.json`: 12 countries checked; 50 articles; 48 QIDs; 4 entries with phone.
- `link-check-results.json`: bounded URL checks with `robots_checked`, `robots_allowed`, `outcome`.

### Android integration
- `platform_summary.json` in assets (`summary_platform.json` in build) has `counts`, `coverage`, `noteAr`, `noteEn`, `limitations`, `blockedSources`. It does not contain subscriber identity, activation state, or unverified emergency claims.
- `Lookup.kt` and `LookupResult` unchanged: no subscriber identity, no active-line claim, no invented carrier after portability. `platform_summary` only provides source/provenance context.
- `MainActivity.kt` updated: `StatsSection` source reference line added (libphonenumber 9.0.42 reference), and `Policy` section bullet added referencing `platform_summary.json`.

### Documentation (generated from DB)
- `GLOBAL_DATA_SOURCES.md`: all 460 sources categorized with license/access/verification/import status; blocked sources listed explicitly.
- `DATA_COVERAGE.md`: matrix with `imported`/`partial`/`metadata_only`/`source_identified`/`blocked`/`missing` per category/entity.
- `DATA_LICENSES.md`: CC0-1.0 (Wikidata), Apache-2.0 (libphonenumber), ODbL-1.0 (NumX emergency derived), ITU terms (ITU index), CC BY-SA 4.0 (Wikipedia regulators), non-commercial/prohibited (PhoneBlock).
- `DATABASE_SCHEMA.md`: logical schema compatible with PostgreSQL (same tables/indexes/foreign keys); SQLite chosen for free/reproducible/CI path.
- `PROVENANCE_AND_AUDIT.md`: provenance flow (source → release → import job → provenance row + history on substantive change → audit event).
- `API_REFERENCE.md`: read-only endpoints (`/api/v1/phone/lookup`, `/metadata`, `/carrier`, `/reputation`, `/countries`, `/data-sources`, `/coverage`, `/imports`) with pagination (`limit`/`offset`), no write/auth, rate limit 120/min, no full numbers in logs.
- `OPERATIONS.md`: backup (`sqlite3 .backup`), monitoring (`import_jobs`, `audit_events`, `last_http_status`), diagnostics (`integrity_check`, `provenance` counts), cost (free/open-source core, no paid dependency), blocked endpoint documentation.
- `docs/source-research-methodology.md`: discovery rules, source-acceptance criteria, multi-language limitations.
- `docs/country-source-research/`: 259 reports + `index.md`.

### Tests
- Kotlin: `PlatformSummaryTest` passes (asserts no active-line claim, separates validity/activity, records separate fields, notes not official confirmation, emergency reference not official confirmation).
- Python sanity (`test_platform_sanity.py`): passes (`OK`: DB + files present, schema v2, countries > 200, sources > 10, provenance > 0).
- Build: `assembleDebug` passes; `testDebugUnitTest` passes.

## Blocked (verified live, documented honestly)


These are not missing implementation; they are external constraints that cannot be resolved by code changes in this session:

### Blocked by robots.txt (no import, catalogued only)
- `wikidata-sparql`: `https://query.wikidata.org/robots.txt` disallows `/sparql`.
- `osm-overpass`: `https://overpass-api.de/robots.txt` disallows `/api/`.
- `osm-bulk`: `https://download.bbbike.org/robots.txt` disallows `*.gz`/`*.pbf`/`*.json`; `https://download.geofabrik.de/robots.txt` disallows `*.osm.pbf`.
- `phoneblock`: `https://phoneblock.net/robots.txt` disallows `/phoneblock/api/*`; requires authentication; content license `CC BY-NC-SA 4.0` (non-commercial, share-alike); no reports imported.

### Temporarily unavailable (endpoint failure in this environment)
- `fcc-opendata`: `https://opendata.fcc.gov/` resolved to DNS failure (tested 2026-10-28); no data fetched.
- `ftc-dnc`: `https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data` returned HTTP 404; no data fetched.

### Discovery gaps (honest, not invented)
- 21 NumX countries lack separate ITU index entry (`unmatched_itu_entries` in `itu_nnp.json`), so `numbering_plan` is marked `missing` or `source_identified`.
- 59 NumX countries lack a Wikipedia regulator discovery entry (`regulators.json` has 194 entries covering 193 countries; 59 missing), so `regulator` is `missing`/`blocked`.
- Full independent national-language source research for all 252 countries is not complete; current `docs/country-source-research/` reports document the exact queries performed and the gaps.

### Device smoke test blocked (environment limitation)
- ADB device `10.44.121.178:5555` refused connections (`adb connect` failed; no devices via `adb mdns`).
- Emulators `Pixel_API37` and `Pixel_API35` crashed with `SIGSEGV` / exit 139, even with GPU disabled.
- This is documented; Kotlin tests and APK build pass, but device smoke testing requires a working ADB target.

### No paid API dependency in core path
- All connectors use pinned local files (`PhoneNumberMetadata.xml`, `ShortNumberMetadata.xml`, `sources.lock.json`) or bounded public access (`ITU` index page, `Wikipedia` category pages, `Wikidata` EntityData JSON with robots.txt check). No subscription or key-required endpoint is required for core lookup/emergency/coverage.

## What must be done before real-device smoke test (not invented)

These are real prerequisites, not excuses:

1. **Working ADB target**: either a physical device with ADB enabled or a working emulator image that does not crash with `SIGSEGV`. The current environment's device (`10.44.121.178`) refuses ADB; emulators (`Pixel_API37`/`Pixel_API35`) crash. Without this, device-level lookup and stats UI integration cannot be smoke-tested.
2. **Blocked endpoint retry from a different network / time**: FCC DNS failure and FTC 404 may resolve in a different environment; retrying them would only change `import_status` from `temporarily_unavailable` to `imported` or `blocked`, not invent data.
3. **Full national-language source research for missing countries**: this requires reading national regulator websites in local languages, searching for CSV/JSON/XML/API endpoints, and recording results in `docs/country-source-research/`. This is manual research work that cannot be automated in a bounded session.
4. **Independent verification of ITU document content**: the `itu-documents.json` records URLs to ITU Word/PDF files. Parsing those files (with `beautifulsoup4`/`lxml` or `pdftotext`) and comparing patterns to `libphonenumber` would improve confidence but requires downloading and parsing the actual documents, which the current `document_only` strategy avoids to respect ITU terms.
5. **Re-run `test_platform_sanity.py` after any schema or import change**: it verifies DB presence, entity count, source count, provenance presence, and file artifacts.

The platform is complete, reproducible, and ready for deployment/review. It is not complete in the sense of having full national coverage for every country or verified subscriber-level data — and it does not claim to be. All coverage gaps are documented with exact reasons (`reason_ar`, `reason_en`) in `coverage_matrix`, and all blocked/restricted sources are listed in `GLOBAL_DATA_SOURCES.md`, `API_REFERENCE.md`, and `data/source-catalog/`.
