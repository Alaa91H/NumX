"""Build the global country/source catalog and seed the platform database.

The catalog is deliberately explicit about *why* an entity or source is
included.  ITU National Numbering Plans are the primary numbering index; the
Wikipedia regulator table is a discovery index only and is never described as
official verification.  Sources whose terms are unclear remain catalogued but
are not imported.
"""

from __future__ import annotations

import csv
import json
import re
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Iterable, Mapping

from . import db, paths

TOOL_VERSION = "1.0.0"
METHODOLOGY = (
    "Entities come from the NumX 252-country asset, matched to ITU National "
    "Numbering Plan entries and the Wikipedia telecommunications-regulator "
    "discovery table. A match proves only that an index entry exists; it does "
    "not prove that a plan is current or that a regulator website is official."
)
CORE_SOURCE_IDS = {
    "libphonenumber-metadata",
    "libphonenumber-short",
    "libphonenumber-carrier",
    "numx-emergency-reference",
    "itu-nnp-index",
    "wikipedia-regulators",
    "wikidata-entitydata",
    "wikidata-sparql",
    "osm-overpass",
    "osm-bulk",
    "phoneblock",
    "fcc-opendata",
    "ftc-dnc",
}


def _today() -> str:
    return date.today().isoformat()


def _next_review(days: int = 90) -> str:
    return (date.today() + timedelta(days=days)).isoformat()


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_app_countries() -> list[dict[str, Any]]:
    return _read_json(paths.APP_COUNTRIES)["countries"]


def load_itu_seed() -> dict[str, Any]:
    return _read_json(paths.ITU_SEED)


def load_regulator_seed() -> dict[str, Any]:
    return _read_json(paths.REGULATOR_SEED)


def load_sources_lock() -> dict[str, Any]:
    return _read_json(paths.SOURCES_LOCK)


def _slug(value: str) -> str:
    value = value.lower()
    value = re.sub(r"[^a-z0-9]+", "-", value)
    return value.strip("-")


def _clean_url(value: str | None) -> str | None:
    """Remove line wrapping that appears in the ITU index HTML."""

    if not value:
        return value
    return re.sub(r"\s+", "", value)


def _base_source(
    *,
    source_id: str,
    source_name: str,
    source_owner: str,
    source_url: str,
    source_category: str,
    data_categories: list[str],
    access_method: str,
    license_name: str,
    license_url: str | None,
    redistribution_allowed: str,
    legal_review_status: str,
    quality_status: str,
    verification_status: str,
    official_source: bool = False,
    primary_or_secondary: str = "secondary",
    country_iso2: str | None = None,
    entity_id: str | None = None,
    geographic_scope: str = "global",
    documentation_url: str | None = None,
    api_base_url: str | None = None,
    download_url: str | None = None,
    authentication_required: bool = False,
    free_access: bool = True,
    commercial_subscription_required: bool = False,
    commercial_use_allowed: str = "unknown",
    attribution_required: bool = False,
    share_alike_required: bool = False,
    retention_restrictions: str | None = None,
    api_rate_limits: str | None = None,
    terms_of_service_url: str | None = None,
    privacy_policy_url: str | None = None,
    source_language: str = "en",
    expected_update_frequency: str = "unknown",
    source_release_date: str | None = None,
    source_revision: str | None = None,
    known_limitations: str = "",
    coverage_notes: str = "",
    import_status: str = "not_imported",
    technical_review_status: str = "not_checked",
    discovery_source_id: str | None = None,
    next_review_due_at: str | None = None,
) -> dict[str, Any]:
    return {
        "source_id": source_id,
        "source_name": source_name,
        "source_owner": source_owner,
        "country_iso2": country_iso2,
        "entity_id": entity_id,
        "geographic_scope": geographic_scope,
        "source_category": source_category,
        "data_categories": data_categories,
        "official_source": db.as_bool(official_source),
        "primary_or_secondary": primary_or_secondary,
        "source_url": source_url,
        "documentation_url": documentation_url,
        "api_base_url": api_base_url,
        "download_url": download_url,
        "access_method": access_method,
        "authentication_required": db.as_bool(authentication_required),
        "free_access": db.as_bool(free_access),
        "commercial_subscription_required": db.as_bool(commercial_subscription_required),
        "license_name": license_name,
        "license_url": license_url,
        "redistribution_allowed": redistribution_allowed,
        "commercial_use_allowed": commercial_use_allowed,
        "attribution_required": db.as_bool(attribution_required),
        "share_alike_required": db.as_bool(share_alike_required),
        "retention_restrictions": retention_restrictions,
        "api_rate_limits": api_rate_limits,
        "terms_of_service_url": terms_of_service_url,
        "privacy_policy_url": privacy_policy_url,
        "source_language": source_language,
        "expected_update_frequency": expected_update_frequency,
        "last_checked_at": None,
        "last_successful_access_at": None,
        "last_successful_import_at": None,
        "source_release_date": source_release_date,
        "source_revision": source_revision,
        "known_limitations": known_limitations,
        "coverage_notes": coverage_notes,
        "quality_status": quality_status,
        "verification_status": verification_status,
        "legal_review_status": legal_review_status,
        "technical_review_status": technical_review_status,
        "import_status": import_status,
        "discovery_source_id": discovery_source_id,
        "last_http_status": None,
        "last_error": None,
        "next_review_due_at": next_review_due_at or _next_review(),
    }


def build_core_sources(lock: Mapping[str, Any]) -> list[dict[str, Any]]:
    generated = lock.get("generatedAt") or _today()
    sources: list[dict[str, Any]] = []

    sources.append(
        _base_source(
            source_id="libphonenumber-metadata",
            source_name="libphonenumber PhoneNumberMetadata",
            source_owner="Google libphonenumber project",
            source_url="https://github.com/google/libphonenumber",
            documentation_url="https://github.com/google/libphonenumber/blob/master/README.md",
            download_url=lock["sources"]["PhoneNumberMetadata.xml"]["url"],
            source_category="numbering_plan_reference",
            data_categories=["numbering_plan", "fixed_line", "mobile", "non_geographic", "voip"],
            access_method="open_download",
            license_name="Apache-2.0",
            license_url="https://github.com/google/libphonenumber/blob/master/LICENSE",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            attribution_required=True,
            legal_review_status="approved",
            quality_status="reference_metadata",
            verification_status="reference_verified_against_sha256",
            official_source=False,
            primary_or_secondary="secondary",
            source_release_date=generated,
            source_revision=lock["sources"]["PhoneNumberMetadata.xml"]["version"],
            known_limitations=(
                "Reference implementation, not a national numbering authority. "
                "Ranges can lag regulator changes. It never proves a line is active."
            ),
            coverage_notes="Parsed locally; patterns and example numbers imported.",
            import_status="not_imported",
            technical_review_status="local_file_verified",
        )
    )
    sources.append(
        _base_source(
            source_id="libphonenumber-short",
            source_name="libphonenumber ShortNumberMetadata",
            source_owner="Google libphonenumber project",
            source_url="https://github.com/google/libphonenumber",
            download_url=lock["sources"]["ShortNumberMetadata.xml"]["url"],
            source_category="short_code_reference",
            data_categories=["short_codes_emergency", "services"],
            access_method="open_download",
            license_name="Apache-2.0",
            license_url="https://github.com/google/libphonenumber/blob/master/LICENSE",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            attribution_required=True,
            legal_review_status="approved",
            quality_status="reference_metadata",
            verification_status="reference_verified_against_sha256",
            source_release_date=generated,
            source_revision=lock["sources"]["ShortNumberMetadata.xml"]["version"],
            known_limitations=(
                "Short-code patterns are not a complete national register and can "
                "vary by carrier. Emergency numbers are reference data, not an "
                "official confirmation."
            ),
            coverage_notes="Patterns imported; the concrete NumX emergency list is a separate source.",
            technical_review_status="local_file_verified",
        )
    )
    sources.append(
        _base_source(
            source_id="libphonenumber-carrier",
            source_name="libphonenumber carrier prefix mapping",
            source_owner="Google libphonenumber project",
            source_url="https://github.com/google/libphonenumber",
            source_category="carrier_reference",
            data_categories=["operator_ranges", "allocated_carrier"],
            access_method="library_data",
            license_name="Apache-2.0",
            license_url="https://github.com/google/libphonenumber/blob/master/LICENSE",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            attribution_required=True,
            legal_review_status="approved",
            quality_status="reference_metadata",
            verification_status="reference_verified",
            source_release_date=generated,
            source_revision="2.42",
            known_limitations=(
                "Allocated-prefix reference only. The mapping can be stale after "
                "mobile number portability; it is not the current carrier."
            ),
            coverage_notes="Imported from the bundled carrier data; no live observations.",
            technical_review_status="local_file_verified",
        )
    )
    sources.append(
        _base_source(
            source_id="numx-emergency-reference",
            source_name="NumX emergency and service reference list",
            source_owner="NumX project (derived from libphonenumber and EmergencyNumberAPI)",
            source_url="https://github.com/Alaa91H/NumX",
            source_category="emergency_reference",
            data_categories=["short_codes_emergency", "services"],
            access_method="local_file",
            license_name="Apache-2.0 AND ODbL-1.0",
            license_url="https://opendatacommons.org/licenses/odbl/1.0/",
            redistribution_allowed="conditional",
            commercial_use_allowed="yes",
            attribution_required=True,
            share_alike_required=True,
            legal_review_status="conditional",
            quality_status="curated_reference",
            verification_status="reference_not_officially_confirmed",
            known_limitations=(
                "Reference data only. Numbers were not confirmed with every "
                "national authority; emergency dialing must be verified locally. "
                "ODbL share-alike applies to the derived database."
            ),
            coverage_notes="Imported as short-code/service records; no E.164 value is invented.",
            technical_review_status="validated_against_asset",
        )
    )
    sources.append(
        _base_source(
            source_id="itu-nnp-index",
            source_name="ITU National Numbering Plans index",
            source_owner="International Telecommunication Union (ITU)",
            source_url="https://www.itu.int/oth/T0202.aspx?parent=T0202",
            documentation_url="https://www.itu.int/ITU-T/inr/nnp/index.html",
            source_category="numbering_plan_index",
            data_categories=["numbering_plan"],
            access_method="document_only",
            license_name="ITU terms of use",
            license_url="https://www.itu.int/en/copyright/Pages/default.aspx",
            redistribution_allowed="conditional",
            commercial_use_allowed="unknown",
            attribution_required=True,
            legal_review_status="review_required",
            quality_status="official_index",
            verification_status="official_index_reachable",
            official_source=True,
            primary_or_secondary="primary",
            source_language="en",
            expected_update_frequency="irregular",
            known_limitations=(
                "The index links to Word/PDF documents. Only plan metadata and "
                "URLs are stored; documents are not redistributed."
            ),
            coverage_notes="Country plan pages discovered and link-checked.",
            import_status="metadata_only",
            technical_review_status="reachable",
        )
    )
    sources.append(
        _base_source(
            source_id="wikipedia-regulators",
            source_name="Wikipedia: List of telecommunications regulatory bodies",
            source_owner="Wikipedia contributors",
            source_url="https://en.wikipedia.org/wiki/List_of_telecommunications_regulatory_bodies",
            source_category="regulator_discovery_index",
            data_categories=["regulator"],
            access_method="public_search",
            license_name="CC BY-SA 4.0",
            license_url="https://creativecommons.org/licenses/by-sa/4.0/",
            redistribution_allowed="conditional",
            commercial_use_allowed="yes",
            attribution_required=True,
            share_alike_required=True,
            legal_review_status="conditional",
            quality_status="discovery_index",
            verification_status="discovery_only",
            source_language="en",
            known_limitations=(
                "Discovery index only. Regulator names and URLs are not proof of "
                "official ownership or current authority. Verify independently."
            ),
            coverage_notes="Used to discover candidate national regulators.",
            import_status="metadata_only",
            technical_review_status="reachable",
        )
    )
    sources.append(
        _base_source(
            source_id="wikidata-entitydata",
            source_name="Wikidata EntityData JSON",
            source_owner="Wikimedia Foundation / Wikidata contributors",
            source_url="https://www.wikidata.org/wiki/Special:EntityData/",
            documentation_url="https://www.wikidata.org/wiki/Wikidata:Data_access",
            source_category="organization_directory",
            data_categories=["business_directory", "government_directory", "organization_identity"],
            access_method="public_api",
            license_name="CC0-1.0",
            license_url="https://creativecommons.org/publicdomain/zero/1.0/",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            attribution_required=False,
            legal_review_status="approved",
            quality_status="community_curated",
            verification_status="community_curated",
            source_language="mul",
            known_limitations=(
                "Community-curated; phone numbers may be old, incomplete, or refer "
                "to a switchboard. EntityData is used only after a QID is found on "
                "an allowed Wikipedia page. No subscriber identity is inferred."
            ),
            coverage_notes="Bounded sample of organization entries for selected countries.",
            technical_review_status="allowed_by_robots",
        )
    )
    sources.append(
        _base_source(
            source_id="wikidata-sparql",
            source_name="Wikidata Query Service (SPARQL)",
            source_owner="Wikimedia Foundation",
            source_url="https://query.wikidata.org/sparql",
            source_category="organization_directory",
            data_categories=["business_directory", "government_directory"],
            access_method="public_api",
            license_name="CC0-1.0",
            license_url="https://creativecommons.org/publicdomain/zero/1.0/",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            legal_review_status="approved",
            quality_status="blocked_endpoint",
            verification_status="blocked_by_robots",
            known_limitations=(
                "query.wikidata.org/robots.txt disallows /sparql. The endpoint is "
                "recorded for transparency and is not used by any connector."
            ),
            coverage_notes="Blocked by robots.txt; no query is sent.",
            import_status="blocked_by_robots",
            technical_review_status="blocked_by_robots",
        )
    )
    sources.append(
        _base_source(
            source_id="osm-overpass",
            source_name="OpenStreetMap Overpass API",
            source_owner="OpenStreetMap contributors / Overpass API operators",
            source_url="https://overpass-api.de/api/interpreter",
            documentation_url="https://wiki.openstreetmap.org/wiki/Overpass_API",
            source_category="poi_directory",
            data_categories=["business_directory", "government_directory", "coordinates"],
            access_method="public_api",
            license_name="ODbL-1.0",
            license_url="https://opendatacommons.org/licenses/odbl/1.0/",
            redistribution_allowed="conditional",
            commercial_use_allowed="yes",
            attribution_required=True,
            share_alike_required=True,
            legal_review_status="conditional",
            quality_status="blocked_endpoint",
            verification_status="blocked_by_robots",
            known_limitations=(
                "overpass-api.de/robots.txt disallows /api/. Bulk Geofabrik and "
                "BBBike files are also disallowed by their robots.txt. No OSM "
                "records are imported; the source remains catalogued."
            ),
            coverage_notes="Blocked by robots.txt; no request is sent by the connector.",
            import_status="blocked_by_robots",
            technical_review_status="blocked_by_robots",
        )
    )
    sources.append(
        _base_source(
            source_id="osm-bulk",
            source_name="OpenStreetMap bulk extracts (Geofabrik/BBBike)",
            source_owner="OpenStreetMap contributors / extract providers",
            source_url="https://download.geofabrik.de/",
            documentation_url="https://wiki.openstreetmap.org/wiki/Planet.osm",
            source_category="poi_directory",
            data_categories=["business_directory", "government_directory", "coordinates"],
            access_method="open_download",
            license_name="ODbL-1.0",
            license_url="https://opendatacommons.org/licenses/odbl/1.0/",
            redistribution_allowed="conditional",
            commercial_use_allowed="yes",
            attribution_required=True,
            share_alike_required=True,
            legal_review_status="conditional",
            quality_status="blocked_endpoint",
            verification_status="blocked_by_robots",
            known_limitations=(
                "The tested extract hosts disallow .osm.pbf/.gz downloads in "
                "robots.txt. No bulk OSM file is downloaded or imported."
            ),
            coverage_notes="Blocked by robots.txt; no download is attempted.",
            import_status="blocked_by_robots",
            technical_review_status="blocked_by_robots",
        )
    )
    sources.append(
        _base_source(
            source_id="phoneblock",
            source_name="PhoneBlock spam-report API and community data",
            source_owner="PhoneBlock / Bernhard Haumacher",
            source_url="https://phoneblock.net/phoneblock/api",
            api_base_url="https://phoneblock.net/phoneblock/api/num",
            documentation_url="https://github.com/haumacher/phoneblock",
            source_category="reputation",
            data_categories=["nuisance_fraud_reports"],
            access_method="public_api",
            authentication_required=True,
            free_access=True,
            commercial_subscription_required=False,
            license_name="CC BY-NC-SA 4.0 (content) / GPL-3.0 (code)",
            license_url="https://creativecommons.org/licenses/by-nc-sa/4.0/",
            redistribution_allowed="prohibited",
            commercial_use_allowed="no",
            attribution_required=True,
            share_alike_required=True,
            legal_review_status="review_required",
            quality_status="restricted_source",
            verification_status="restricted",
            known_limitations=(
                "The API requires authentication and its data endpoints are "
                "disallowed by robots.txt. The content licence is non-commercial "
                "and share-alike. No reports are imported."
            ),
            coverage_notes="Catalogued only; no authentication bypass is attempted.",
            import_status="blocked_by_robots",
            technical_review_status="blocked_by_robots",
        )
    )
    sources.append(
        _base_source(
            source_id="fcc-opendata",
            source_name="FCC Open Data",
            source_owner="U.S. Federal Communications Commission",
            source_url="https://opendata.fcc.gov/",
            source_category="regulator_dataset",
            data_categories=["numbering_plan", "reputation"],
            access_method="public_api",
            license_name="U.S. government public domain",
            license_url="https://www.usa.gov/government-copyright",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            legal_review_status="approved",
            quality_status="unavailable_endpoint",
            verification_status="dns_failure",
            official_source=True,
            primary_or_secondary="primary",
            country_iso2="US",
            entity_id="COUNTRY:US",
            geographic_scope="country",
            known_limitations=(
                "DNS resolution failed in the execution environment on 2026-10-28. "
                "No data was fetched or imported."
            ),
            coverage_notes="Endpoint unavailable; retry from a network with DNS access.",
            import_status="temporarily_unavailable",
            technical_review_status="dns_failure",
        )
    )
    sources.append(
        _base_source(
            source_id="ftc-dnc",
            source_name="FTC Do Not Call data set",
            source_owner="U.S. Federal Trade Commission",
            source_url="https://www.ftc.gov/policy-notices/open-government/data-sets/do-not-call-data",
            source_category="reputation",
            data_categories=["nuisance_fraud_reports"],
            access_method="open_download",
            license_name="U.S. government public domain",
            license_url="https://www.usa.gov/government-copyright",
            redistribution_allowed="yes",
            commercial_use_allowed="yes",
            legal_review_status="approved",
            quality_status="unavailable_endpoint",
            verification_status="http_404",
            official_source=True,
            primary_or_secondary="primary",
            country_iso2="US",
            entity_id="COUNTRY:US",
            geographic_scope="country",
            known_limitations=(
                "The documented URL returned HTTP 404 on 2026-10-28. No data was "
                "fetched or imported."
            ),
            coverage_notes="Endpoint unavailable; locate the current data-set page before retrying.",
            import_status="temporarily_unavailable",
            technical_review_status="http_404",
        )
    )
    return sources


def build_itu_sources(itu: Mapping[str, Any], app_countries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    names = {country["iso2"]: country for country in app_countries}
    sources: list[dict[str, Any]] = []
    for entry in itu.get("entries", []):
        iso2 = entry["iso2"]
        country = names.get(iso2, {})
        sources.append(
            _base_source(
                source_id=f"itu-nnp-{iso2.lower()}",
                source_name=f"ITU National Numbering Plan — {country.get('nameEn', entry['itu_name'])}",
                source_owner="International Telecommunication Union (ITU)",
                country_iso2=iso2,
                entity_id=f"COUNTRY:{iso2}",
                geographic_scope="country",
                source_category="numbering_plan",
                data_categories=["numbering_plan"],
                source_url=_clean_url(entry["url"]) or entry["url"],
                documentation_url=itu.get("source_url"),
                access_method="document_only",
                license_name=itu.get("license_name", "ITU terms of use"),
                license_url=itu.get("license_url"),
                redistribution_allowed="conditional",
                commercial_use_allowed="unknown",
                attribution_required=True,
                legal_review_status="review_required",
                quality_status="official_index",
                verification_status="official_index_entry",
                official_source=True,
                primary_or_secondary="primary",
                source_language="en",
                expected_update_frequency="irregular",
                known_limitations=(
                    "ITU index page only; the linked document may be old. Full "
                    "plan content is not redistributed. A plan does not prove "
                    "that any individual line is active."
                ),
                coverage_notes="Plan metadata and URL only.",
                import_status="metadata_only",
                technical_review_status="not_checked",
                discovery_source_id="itu-nnp-index",
            )
        )
    return sources


def build_global_sources(itu: Mapping[str, Any]) -> list[dict[str, Any]]:
    sources: list[dict[str, Any]] = []
    for network in itu.get("global_networks", []):
        slug = _slug(network["entity_id"].split(":", 1)[1])
        sources.append(
            _base_source(
                source_id=f"itu-global-{slug}",
                source_name=f"ITU global numbering plan — {network['name_en']}",
                source_owner="International Telecommunication Union (ITU)",
                entity_id=network["entity_id"],
                geographic_scope="global",
                source_category="global_numbering_plan",
                data_categories=["satellite_networks", "numbering_plan"],
                source_url=_clean_url(network["itu_url"]) or network["itu_url"],
                download_url=_clean_url(network.get("document_url")),
                documentation_url=itu.get("source_url"),
                access_method="document_only",
                license_name=itu.get("license_name", "ITU terms of use"),
                license_url=itu.get("license_url"),
                redistribution_allowed="conditional",
                commercial_use_allowed="unknown",
                attribution_required=True,
                legal_review_status="review_required",
                quality_status="official_document",
                verification_status="official_document_reachable",
                official_source=True,
                primary_or_secondary="primary",
                source_language="en",
                expected_update_frequency="irregular",
                known_limitations=(
                    "Shared global code documented by ITU. The document date varies; "
                    "no range-level import is claimed."
                ),
                coverage_notes="Document metadata only; no bulk redistribution.",
                import_status="metadata_only",
                technical_review_status="not_checked",
                discovery_source_id="itu-nnp-index",
            )
        )
    return sources


def build_regulator_sources(regulators: Mapping[str, Any], app_countries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    names = {country["iso2"]: country for country in app_countries}
    sources: list[dict[str, Any]] = []
    for entry in regulators.get("entries", []):
        iso2 = entry["iso2"]
        country = names.get(iso2, {})
        for index, regulator in enumerate(entry.get("regulators", [])):
            if not regulator.get("name"):
                continue
            source_id = f"regulator-{iso2.lower()}" if index == 0 else f"regulator-{iso2.lower()}-{index + 1}"
            urls = regulator.get("urls") or []
            sources.append(
                _base_source(
                    source_id=source_id,
                    source_name=regulator["name"],
                    source_owner=regulator["name"],
                    country_iso2=iso2,
                    entity_id=f"COUNTRY:{iso2}",
                    geographic_scope="country",
                    source_category="regulator",
                    data_categories=["regulator", "numbering_plan"],
                    source_url=urls[0] if urls else regulators.get("source_url"),
                    documentation_url=regulators.get("source_url"),
                    access_method="public_search",
                    license_name="unknown",
                    license_url=None,
                    redistribution_allowed="unknown",
                    commercial_use_allowed="unknown",
                    legal_review_status="review_required",
                    quality_status="discovery_only",
                    verification_status="discovery_only",
                    official_source=False,
                    primary_or_secondary="secondary",
                    source_language="en",
                    expected_update_frequency="unknown",
                    known_limitations=(
                        "Regulator name and URL were discovered from a Wikipedia "
                        "table. Ownership, current authority, and website terms are "
                        "not verified; no data is imported from this source yet."
                    ),
                    coverage_notes="Discovery record only.",
                    import_status="not_imported",
                    technical_review_status="not_checked",
                    discovery_source_id="wikipedia-regulators",
                )
            )
    return sources


def build_sources(
    app_countries: list[dict[str, Any]],
    itu: Mapping[str, Any],
    regulators: Mapping[str, Any],
    lock: Mapping[str, Any],
) -> list[dict[str, Any]]:
    sources = []
    sources.extend(build_core_sources(lock))
    sources.extend(build_itu_sources(itu, app_countries))
    sources.extend(build_global_sources(itu))
    sources.extend(build_regulator_sources(regulators, app_countries))
    # Deterministic order and unique IDs.
    unique: dict[str, dict[str, Any]] = {}
    for source in sources:
        if source["source_id"] in unique:
            raise ValueError(f"duplicate source_id: {source['source_id']}")
        unique[source["source_id"]] = source
    return [unique[key] for key in sorted(unique)]


def build_entities(
    app_countries: list[dict[str, Any]],
    itu: Mapping[str, Any],
    regulators: Mapping[str, Any],
    sources: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    itu_by_iso = {entry["iso2"]: entry for entry in itu.get("entries", [])}
    regulator_by_iso: dict[str, list[dict[str, Any]]] = {}
    for entry in regulators.get("entries", []):
        regulator_by_iso[entry["iso2"]] = entry.get("regulators", [])
    source_by_id = {source["source_id"]: source for source in sources}
    entities: list[dict[str, Any]] = []
    for country in app_countries:
        iso2 = country["iso2"]
        itu_entry = itu_by_iso.get(iso2)
        regs = regulator_by_iso.get(iso2, [])
        first_reg = regs[0] if regs else None
        refs = ["libphonenumber-metadata", "numx-emergency-reference"]
        if itu_entry:
            refs.append(f"itu-nnp-{iso2.lower()}")
        if first_reg:
            refs.append(f"regulator-{iso2.lower()}")
        inclusion_source = "ITU National Numbering Plans index" if itu_entry else "NumX app metadata"
        inclusion_url = (
            _clean_url(itu_entry["url"]) if itu_entry else "https://github.com/Alaa91H/NumX"
        )
        entities.append(
            {
                "entity_id": f"COUNTRY:{iso2}",
                "iso2": iso2,
                "name_en": country["nameEn"],
                "name_ar": country["nameAr"],
                "calling_code": country.get("callingCode"),
                "entity_kind": "country_or_territory",
                "parent_iso2": country.get("mainCountry") or None,
                "support_tier": country.get("support"),
                "itu_nnp_url": _clean_url(itu_entry["url"]) if itu_entry else None,
                "itu_reference": itu_entry["itu_name"] if itu_entry else None,
                "iso_reference": "ISO 3166-1 alpha-2 (XK is a user-assigned code)" if iso2 == "XK" else "ISO 3166-1 alpha-2",
                "regulator_name": first_reg["name"] if first_reg else None,
                "regulator_url": (first_reg.get("urls") or [None])[0] if first_reg else None,
                "source_refs_json": db.json_dumps(refs),
                "notes_ar": (
                    "خطة الترقيم من فهرس ITU؛ الجهة التنظيمية من فهرس اكتشافي "
                    "ولم تُتحقق ملكيتها. لا يعني وجود الخطة أن أي خط نشط."
                    if itu_entry
                    else "لا يوجد إدخال ITU مستقل؛ قد تكون المنطقة مشمولة بخطة "
                    "رمز اتصال مشترك. الحالة غير مؤكدة."
                ),
                "inclusion": {
                    "source": inclusion_source,
                    "url": inclusion_url,
                    "retrieved_at": itu.get("generated_at", _today()),
                },
                "coverage": {},
            }
        )
    for network in itu.get("global_networks", []):
        slug = _slug(network["entity_id"].split(":", 1)[1])
        source_id = f"itu-global-{slug}"
        entities.append(
            {
                "entity_id": network["entity_id"],
                "iso2": None,
                "name_en": network["name_en"],
                "name_ar": network.get("name_ar", network["name_en"]),
                "calling_code": ", ".join(network.get("calling_codes", [])),
                "entity_kind": "global_network",
                "parent_iso2": None,
                "support_tier": "REFERENCE",
                "itu_nnp_url": _clean_url(network.get("itu_url")),
                "itu_reference": network.get("itu_name", network["name_en"]),
                "iso_reference": None,
                "regulator_name": None,
                "regulator_url": None,
                "source_refs_json": db.json_dumps([source_id]),
                "notes_ar": "شبكة عالمية مشتركة وفق وثيقة ITU؛ لا تمثل خطة دولة.",
                "inclusion": {
                    "source": "ITU global numbering documents",
                    "url": _clean_url(network.get("itu_url")),
                    "retrieved_at": itu.get("generated_at", _today()),
                },
                "coverage": {},
            }
        )
    return sorted(entities, key=lambda item: item["entity_id"])


def _preserve_runtime_fields(connection: Any, source: dict[str, Any]) -> dict[str, Any]:
    existing = connection.execute(
        "SELECT * FROM source_registry WHERE source_id=?", (source["source_id"],)
    ).fetchone()
    if existing is None:
        return source
    merged = dict(source)
    for field in (
        "last_checked_at",
        "last_successful_access_at",
        "last_successful_import_at",
        "technical_review_status",
        "import_status",
        "last_http_status",
        "last_error",
        "verification_status",
        "quality_status",
    ):
        if existing[field] is not None and existing[field] not in ("", "not_imported", "not_checked"):
            merged[field] = existing[field]
    if existing["download_url"] and not merged.get("download_url"):
        merged["download_url"] = existing["download_url"]
    return merged


def seed_database(
    connection: Any,
    *,
    app_countries: list[dict[str, Any]] | None = None,
    itu: Mapping[str, Any] | None = None,
    regulators: Mapping[str, Any] | None = None,
    lock: Mapping[str, Any] | None = None,
) -> dict[str, int]:
    app_countries = app_countries or load_app_countries()
    itu = itu or load_itu_seed()
    regulators = regulators or load_regulator_seed()
    lock = lock or load_sources_lock()
    sources = build_sources(app_countries, itu, regulators, lock)
    entities = build_entities(app_countries, itu, regulators, sources)
    now = db.utc_now()
    generated = lock.get("generatedAt") or _today()
    with connection:
        for source in sources:
            db.upsert_source(connection, _preserve_runtime_fields(connection, source))
        for entity in entities:
            row = dict(entity)
            row.pop("inclusion", None)
            row.pop("coverage", None)
            row["created_at"] = now
            row["updated_at"] = now
            db.upsert(connection, "countries_and_regions", row, ("entity_id",))
        # One dataset row per source/category.  This makes the source registry
        # useful without pretending every row is a separate downloadable file.
        for source in sources:
            for category in source.get("data_categories", []):
                dataset_id = f"{source['source_id']}:{category}"
                db.upsert(
                    connection,
                    "source_datasets",
                    {
                        "dataset_id": dataset_id,
                        "source_id": source["source_id"],
                        "dataset_name": f"{source['source_name']} — {category}",
                        "data_category": category,
                        "format": {
                            "open_download": "file",
                            "public_api": "api",
                            "document_only": "document",
                            "public_search": "html",
                            "local_file": "json/xml",
                            "library_data": "library",
                        }.get(source["access_method"], "unknown"),
                        "schema_json": db.json_dumps({"source_id": source["source_id"], "category": category}),
                        "license_name": source["license_name"],
                        "license_url": source.get("license_url"),
                        "redistribution_allowed": source["redistribution_allowed"],
                        "coverage_scope": source["geographic_scope"],
                        "known_limitations": source["known_limitations"],
                        "created_at": now,
                        "updated_at": now,
                    },
                    ("dataset_id",),
                )
        release_specs = [
            (
                "libphonenumber-metadata-9.0.42",
                "libphonenumber-metadata",
                lock["sources"]["PhoneNumberMetadata.xml"],
                generated,
            ),
            (
                "libphonenumber-short-9.0.42",
                "libphonenumber-short",
                lock["sources"]["ShortNumberMetadata.xml"],
                generated,
            ),
            (
                "numx-emergency-reference-1",
                "numx-emergency-reference",
                lock["sources"]["EmergencyNumberAPI.json"],
                generated,
            ),
        ]
        for release_id, source_id, entry, release_date in release_specs:
            db.upsert(
                connection,
                "source_releases",
                {
                    "release_id": release_id,
                    "source_id": source_id,
                    "dataset_id": None,
                    "release_date": release_date,
                    "fetched_at": f"{release_date}T00:00:00Z",
                    "content_sha256": entry.get("sha256"),
                    "content_bytes": None,
                    "source_url": entry["url"],
                    "status": "available",
                    "notes": "Pinned in tools/metadata/sources.lock.json",
                },
                ("release_id",),
            )
        # ITU plan metadata (one plan per country index entry).
        for entry in itu.get("entries", []):
            db.upsert(
                connection,
                "numbering_plans",
                {
                    "plan_id": f"itu-{entry['iso2'].lower()}",
                    "country_iso2": entry["iso2"],
                    "plan_name": f"ITU NNP index entry: {entry['itu_name']}",
                    "version": None,
                    "effective_date": None,
                    "source_id": f"itu-nnp-{entry['iso2'].lower()}",
                    "source_release_id": None,
                    "notes": "Index metadata only; document content is not stored.",
                },
                ("plan_id",),
            )
        # Global shared networks (satellite and maritime) have plan metadata
        # but no country ISO code.  The entity id is stored in country_iso2 so
        # coverage can join it without inventing a country.
        for network in itu.get("global_networks", []):
            slug = _slug(network["entity_id"].split(":", 1)[1])
            db.upsert(
                connection,
                "numbering_plans",
                {
                    "plan_id": f"itu-global-{slug}",
                    "country_iso2": network["entity_id"],
                    "plan_name": f"ITU global numbering plan: {network['name_en']}",
                    "version": None,
                    "effective_date": None,
                    "source_id": f"itu-global-{slug}",
                    "source_release_id": None,
                    "notes": "Document metadata only; no range-level content imported.",
                },
                ("plan_id",),
            )
        # libphonenumber plan rows for every territory in the local XML.
        for territory in _libphonenumber_territories():
            iso2 = territory
            db.upsert(
                connection,
                "numbering_plans",
                {
                    "plan_id": f"libphonenumber-{iso2.lower()}",
                    "country_iso2": iso2,
                    "plan_name": f"libphonenumber reference plan for {iso2}",
                    "version": "9.0.42",
                    "effective_date": None,
                    "source_id": "libphonenumber-metadata",
                    "source_release_id": "libphonenumber-metadata-9.0.42",
                    "notes": "Reference plan; not an official national plan.",
                },
                ("plan_id",),
            )
        db.audit(
            connection,
            "catalog.seeded",
            details={
                "entities": len(entities),
                "sources": len(sources),
                "tool_version": TOOL_VERSION,
            },
        )
    return {"entities": len(entities), "sources": len(sources)}


def _libphonenumber_territories() -> list[str]:
    import xml.etree.ElementTree as ET

    if not paths.PHONE_METADATA.exists():
        return []
    root = ET.parse(paths.PHONE_METADATA).getroot()
    territories = root.find("territories")
    if territories is None:
        return []
    return sorted(
        territory.get("id")
        for territory in territories.findall("territory")
        if territory.get("id")
    )


def _coverage_summary(connection: Any, iso2: str | None, entity_id: str) -> dict[str, int]:
    if iso2:
        rows = connection.execute(
            "SELECT status, COUNT(*) AS n FROM coverage_matrix WHERE country_iso2=? GROUP BY status",
            (iso2,),
        ).fetchall()
    else:
        rows = connection.execute(
            "SELECT status, COUNT(*) AS n FROM coverage_matrix WHERE country_iso2=? GROUP BY status",
            (entity_id,),
        ).fetchall()
    return {row["status"]: row["n"] for row in rows}


def write_catalog_files(connection: Any) -> dict[str, str]:
    paths.ensure_dirs()
    countries_path = paths.CATALOG_DIR / "countries.json"
    sources_path = paths.CATALOG_DIR / "source-registry.json"
    catalog_csv = paths.CATALOG_DIR / "country-source-catalog.csv"
    coverage_csv = paths.CATALOG_DIR / "country-coverage-matrix.csv"

    entities = [dict(row) for row in connection.execute("SELECT * FROM countries_and_regions ORDER BY entity_id")]
    source_rows = [dict(row) for row in connection.execute("SELECT * FROM source_registry ORDER BY source_id")]
    for entity in entities:
        entity["source_refs"] = json.loads(entity.pop("source_refs_json") or "[]")
        entity["coverage"] = _coverage_summary(connection, entity["iso2"], entity["entity_id"])
        entity["inclusion"] = {
            "source": "ITU National Numbering Plans index" if entity["itu_nnp_url"] else "NumX app metadata",
            "url": entity["itu_nnp_url"] or "https://github.com/Alaa91H/NumX",
            "retrieved_at": _today(),
        }
    for source in source_rows:
        source["data_categories"] = json.loads(source.pop("data_categories_json") or "[]")

    countries = [entity for entity in entities if entity["entity_kind"] != "global_network"]
    networks = [entity for entity in entities if entity["entity_kind"] == "global_network"]
    payload = {
        "generatedAt": _today(),
        "schemaVersion": db.SCHEMA_VERSION,
        "toolVersion": TOOL_VERSION,
        "methodology": METHODOLOGY,
        "counts": {
            "entities": len(entities),
            "countriesAndTerritories": len(countries),
            "globalNetworks": len(networks),
            "withItuNnp": sum(1 for entity in countries if entity["itu_nnp_url"]),
            "withRegulatorDiscovery": sum(1 for entity in countries if entity["regulator_url"]),
        },
        "countries": countries,
        "globalNetworks": networks,
        "entities": entities,
    }
    countries_path.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    sources_path.write_text(json.dumps(source_rows, ensure_ascii=False, indent=1), encoding="utf-8")

    with catalog_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=catalog_columns())
        writer.writeheader()
        for source in source_rows:
            writer.writerow(catalog_row(source))

    coverage_rows = [dict(row) for row in connection.execute(
        "SELECT * FROM coverage_matrix ORDER BY country_iso2, data_category"
    )]
    with coverage_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=coverage_columns())
        writer.writeheader()
        for row in coverage_rows:
            entity = next((item for item in entities if item["iso2"] == row["country_iso2"] or item["entity_id"] == row["country_iso2"]), None)
            writer.writerow(
                {
                    "entity_id": entity["entity_id"] if entity else row["country_iso2"],
                    "iso2": entity["iso2"] if entity else "",
                    "country_name_en": entity["name_en"] if entity else "",
                    "country_name_ar": entity["name_ar"] if entity else "",
                    "data_category": row["data_category"],
                    "status": row["status"],
                    "source_count": row["source_count"],
                    "imported_record_count": row["imported_record_count"],
                    "provenance_coverage": "" if row["provenance_coverage"] is None else f"{row['provenance_coverage']:.4f}",
                    "freshness_days": "" if row["freshness_days"] is None else row["freshness_days"],
                    "reason_ar": row["reason_ar"],
                    "reason_en": row["reason_en"],
                    "last_verified_at": row["last_verified_at"] or "",
                }
            )
    return {
        "countries": str(countries_path),
        "sources": str(sources_path),
        "catalog_csv": str(catalog_csv),
        "coverage_csv": str(coverage_csv),
    }


def catalog_columns() -> list[str]:
    return [
        "source_id",
        "country_iso2",
        "entity_id",
        "source_name",
        "source_owner",
        "source_category",
        "data_categories",
        "source_url",
        "documentation_url",
        "download_url",
        "api_base_url",
        "access_method",
        "source_language",
        "license_name",
        "license_url",
        "redistribution_allowed",
        "commercial_use_allowed",
        "attribution_required",
        "share_alike_required",
        "official_source",
        "primary_or_secondary",
        "verification_status",
        "quality_status",
        "legal_review_status",
        "technical_review_status",
        "import_status",
        "last_checked_at",
        "last_successful_access_at",
        "last_successful_import_at",
        "last_http_status",
        "last_error",
        "known_limitations",
        "coverage_notes",
        "discovery_source_id",
    ]


def catalog_row(source: Mapping[str, Any]) -> dict[str, Any]:
    return {
        "source_id": source["source_id"],
        "country_iso2": source.get("country_iso2") or "",
        "entity_id": source.get("entity_id") or "",
        "source_name": source["source_name"],
        "source_owner": source["source_owner"],
        "source_category": source["source_category"],
        "data_categories": ";".join(source.get("data_categories", [])),
        "source_url": source["source_url"],
        "documentation_url": source.get("documentation_url") or "",
        "download_url": source.get("download_url") or "",
        "api_base_url": source.get("api_base_url") or "",
        "access_method": source["access_method"],
        "source_language": source["source_language"],
        "license_name": source["license_name"],
        "license_url": source.get("license_url") or "",
        "redistribution_allowed": source["redistribution_allowed"],
        "commercial_use_allowed": source["commercial_use_allowed"],
        "attribution_required": source["attribution_required"],
        "share_alike_required": source["share_alike_required"],
        "official_source": source["official_source"],
        "primary_or_secondary": source["primary_or_secondary"],
        "verification_status": source["verification_status"],
        "quality_status": source["quality_status"],
        "legal_review_status": source["legal_review_status"],
        "technical_review_status": source["technical_review_status"],
        "import_status": source["import_status"],
        "last_checked_at": source.get("last_checked_at") or "",
        "last_successful_access_at": source.get("last_successful_access_at") or "",
        "last_successful_import_at": source.get("last_successful_import_at") or "",
        "last_http_status": source.get("last_http_status") or "",
        "last_error": source.get("last_error") or "",
        "known_limitations": source["known_limitations"],
        "coverage_notes": source["coverage_notes"],
        "discovery_source_id": source.get("discovery_source_id") or "",
    }


def coverage_columns() -> list[str]:
    return [
        "entity_id",
        "iso2",
        "country_name_en",
        "country_name_ar",
        "data_category",
        "status",
        "source_count",
        "imported_record_count",
        "provenance_coverage",
        "freshness_days",
        "reason_ar",
        "reason_en",
        "last_verified_at",
    ]
