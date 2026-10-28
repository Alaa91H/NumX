"""Import libphonenumber's allocated-carrier prefix mapping.

This is an *allocated prefix* observation, not a claim about the current
carrier.  Mobile number portability means the original allocation can differ
from the network serving a number today.
"""

from __future__ import annotations

import re
from typing import Any, Iterable, Mapping

import phonenumbers
from phonenumbers import carrier
from phonenumbers.data import _COUNTRY_CODE_TO_REGION_CODE
from phonenumbers.phonemetadata import PhoneMetadata

from .base import ImportContext
from .common import entity_iso_set, provenance_row

_REGION_CACHE: dict[str, str | None] = {}


def _load_metadata(region: str) -> PhoneMetadata | None:
    if PhoneMetadata.metadata_for_region(region) is not None:
        return PhoneMetadata.metadata_for_region(region)
    try:
        import phonenumbers.data as data

        data._load_region(region)
    except Exception:
        return None
    return PhoneMetadata.metadata_for_region(region)


def region_for_prefix(prefix: str) -> str | None:
    """Resolve an E.164 digit prefix to one region, or ``None`` if ambiguous."""

    if prefix in _REGION_CACHE:
        return _REGION_CACHE[prefix]
    padded = prefix + "0" * max(0, 10 - len(prefix))
    region: str | None = None
    country_code = None
    try:
        number = phonenumbers.parse("+" + padded, None)
        country_code = number.country_code
        region = phonenumbers.region_code_for_number(number)
    except Exception:
        pass
    if region:
        _REGION_CACHE[prefix] = region
        return region
    if country_code is None:
        for code in sorted((int(code) for code in _COUNTRY_CODE_TO_REGION_CODE), reverse=True):
            if prefix.startswith(str(code)):
                country_code = code
                break
    if country_code is None:
        _REGION_CACHE[prefix] = None
        return None
    candidates = phonenumbers.region_codes_for_country_code(country_code)
    national = padded[len(str(country_code)) :]
    matches: list[str] = []
    for candidate in candidates:
        metadata = _load_metadata(candidate)
        if metadata is None:
            continue
        for description in (
            metadata.fixed_line,
            metadata.mobile,
            metadata.general_desc,
        ):
            pattern = getattr(description, "national_number_pattern", None)
            if pattern and re.fullmatch(pattern, national):
                matches.append(candidate)
                break
    result = matches[0] if len(matches) == 1 else None
    _REGION_CACHE[prefix] = result
    return result


class CarrierConnector:
    connector_id = "libphonenumber-carrier-prefixes"
    connector_version = "1.0.0"
    source_id = "libphonenumber-carrier"
    entity_type = "carrier_observation"
    target_table = "carrier_observations"
    conflict_columns = ("source_id", "source_record_id")

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        wanted = entity_iso_set(context.connection)
        for prefix, names in carrier.CARRIER_DATA.items():
            name = names.get("en") if isinstance(names, Mapping) else None
            if not name or not re.fullmatch(r"\d{3,15}", prefix):
                continue
            iso2 = region_for_prefix(prefix)
            if iso2 not in wanted:
                continue
            yield {
                "prefix": prefix,
                "carrier_name": name,
                "country_iso2": iso2,
            }

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        errors = []
        if not re.fullmatch(r"\d{3,15}", str(record.get("prefix", ""))):
            errors.append("invalid E.164 prefix")
        if not record.get("carrier_name"):
            errors.append("missing carrier name")
        return errors

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        return {
            "country_iso2": record["country_iso2"],
            "range_prefix": record["prefix"],
            "carrier_name": record["carrier_name"],
            "carrier_type": "unknown",
            "observation_type": "allocated_carrier",
            "source_id": self.source_id,
            "source_record_id": record["prefix"],
            "observed_at": None,
            "imported_at": context.connection.execute("SELECT datetime('now')").fetchone()[0],
            "confidence": "low",
            "limitations": (
                "Allocated prefix only. Mobile number portability can change the "
                "current carrier; this is not a live network observation."
            ),
        }

    def provenance(self, record, row, context, job_id):
        yield provenance_row(
            field_name="allocated_carrier",
            original_value=f"{record['prefix']}:{record['carrier_name']}",
            normalized_value=record["carrier_name"],
            source_record_id=record["prefix"],
            source_url=context.source_url,
            verification="REFERENCE",
            license_name="Apache-2.0",
            license_url="https://github.com/google/libphonenumber/blob/master/LICENSE",
            transformations="E.164 prefix resolved to one region; no live lookup",
        )
