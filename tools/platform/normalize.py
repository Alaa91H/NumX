"""Phone-number normalization with explicit preservation of the original value.

The platform never treats a numbering-plan match as proof that a line is
active.  It also never invents an E.164 value for short codes or service
numbers that do not follow E.164.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional

import phonenumbers
from phonenumbers import NumberParseException, PhoneNumberFormat, PhoneNumberType

ARABIC_INDIC = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
DIGITS = re.compile(r"\d+")


@dataclass(frozen=True)
class NormalizedPhone:
    original: str
    digits: str
    e164: Optional[str]
    national: Optional[str]
    international: Optional[str]
    country_iso2: Optional[str]
    calling_code: Optional[str]
    number_type: str
    carrier: Optional[str]
    valid: bool
    possible: bool
    error: Optional[str] = None


def normalize_digits(value: str) -> str:
    return re.sub(r"\D", "", value.translate(ARABIC_INDIC))


def parse_phone(raw: str, region: str | None = None) -> NormalizedPhone:
    original = raw.strip()
    digits = normalize_digits(original)
    if not original:
        return NormalizedPhone(original, digits, None, None, None, None, None, "UNKNOWN", None, False, False, "empty")
    if len(digits) < 3 or len(digits) > 20:
        return NormalizedPhone(original, digits, None, None, None, None, None, "UNKNOWN", None, False, False, "length")
    candidate = original
    if original.startswith("00") and not original.startswith("+"):
        candidate = "+" + digits[2:]
    elif original.startswith("+"):
        candidate = "+" + digits
    try:
        number = phonenumbers.parse(candidate, region)
    except NumberParseException as exc:
        return NormalizedPhone(
            original, digits, None, None, None, None, None, "UNKNOWN", None, False, False, str(exc)
        )
    iso2 = phonenumbers.region_code_for_number(number)
    calling_code = f"+{number.country_code}" if number.country_code else None
    valid = phonenumbers.is_valid_number(number)
    possible = phonenumbers.is_possible_number(number)
    carrier = None
    if valid:
        try:
            carrier = phonenumbers.carrier.name_for_number(number, "en") or None
        except Exception:
            carrier = None
    return NormalizedPhone(
        original=original,
        digits=digits,
        e164=phonenumbers.format_number(number, PhoneNumberFormat.E164) if valid else None,
        national=phonenumbers.format_number(number, PhoneNumberFormat.NATIONAL) if valid else None,
        international=phonenumbers.format_number(number, PhoneNumberFormat.INTERNATIONAL) if valid else None,
        country_iso2=iso2,
        calling_code=calling_code,
        number_type=number_type_name(number),
        carrier=carrier,
        valid=valid,
        possible=possible,
        error=None if valid or possible else "invalid",
    )


def number_type_name(number: phonenumbers.PhoneNumber) -> str:
    mapping = {
        PhoneNumberType.MOBILE: "MOBILE",
        PhoneNumberType.FIXED_LINE: "FIXED_LINE",
        PhoneNumberType.FIXED_LINE_OR_MOBILE: "FIXED_LINE_OR_MOBILE",
        PhoneNumberType.TOLL_FREE: "TOLL_FREE",
        PhoneNumberType.PREMIUM_RATE: "PREMIUM_RATE",
        PhoneNumberType.SHARED_COST: "SHARED_COST",
        PhoneNumberType.VOIP: "VOIP",
        PhoneNumberType.PERSONAL_NUMBER: "PERSONAL_NUMBER",
        PhoneNumberType.PAGER: "PAGER",
        PhoneNumberType.UAN: "UAN",
        PhoneNumberType.VOICEMAIL: "VOICEMAIL",
        PhoneNumberType.UNKNOWN: "UNKNOWN",
    }
    return mapping.get(phonenumbers.number_type(number), "UNKNOWN")


def normalize_short_number(raw: str) -> str:
    """Short codes keep their leading zeros; they are not E.164 numbers."""

    return normalize_digits(raw)


def validate_number_input(value: str) -> bool:
    if not value or len(value) > 32:
        return False
    return bool(re.fullmatch(r"[+0-9()\-\s./]+", value))


def range_prefix(national_digits: str, length: int = 5) -> Optional[str]:
    digits = normalize_digits(national_digits)
    if not digits:
        return None
    return digits[:length]


def country_code_for_iso2(iso2: str) -> Optional[int]:
    try:
        return phonenumbers.country_code_for_region(iso2.upper())
    except Exception:
        return None


def region_for_calling_code(calling_code: str) -> Optional[str]:
    digits = normalize_digits(calling_code)
    for region in phonenumbers.SUPPORTED_REGIONS:
        if phonenumbers.country_code_for_region(region) == int(digits):
            return region
    return None
