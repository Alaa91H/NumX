#!/usr/bin/env python3
"""Build the offline metadata assets used by NumX 2.x.

Usage:
    python3 tools/metadata/build_metadata.py            # regenerate assets
    python3 tools/metadata/build_metadata.py --check    # verify assets/lock

The script is deterministic. It reads the vendored libphonenumber XML files,
compiles every mobile/fixed-line pattern into a length-bounded DFA, and writes
compact JSON assets under numx-android/app/src/main/assets/metadata/.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set, Tuple

from babel import Locale
import pycountry

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ASSETS = ROOT / "numx-android" / "app" / "src" / "main" / "assets" / "metadata"
SOURCES = HERE / "sources"
LOCK_FILE = HERE / "sources.lock.json"
OVERLAYS = HERE / "overlays"

METADATA_VERSION = "9.0.42"
PLAN_VERSION = 1
TOOL_VERSION = "1.0.0"

sys.path.insert(0, str(HERE))
from dfa import (  # noqa: E402
    Dfa,
    PatternError,
    compile_pattern,
    count_by_length,
    count_exact,
    enumerate_prefix,
    to_json,
)

EXTRA_TERRITORIES = {
    "AC": ("Ascension Island", "جزيرة أسينشيون", "247"),
    "TA": ("Tristan da Cunha", "تريستان دا كونا", "290"),
    "XK": ("Kosovo", "كوسوفو", "383"),
}
# TA's calling code is +290, shared with SH. libphonenumber has no mobile
# pattern for TA and uses +290 metadata. Declaring it explicitly avoids a
# missing/cross-wired calling code in the catalog.
MISSING_CALLING_CODES = {
    "AQ": "672",
    "BV": "47",
    "GS": "500",
    "HM": "672",
    "PN": "64",
    "TF": "262",
    "UM": "1",
}

# Curated Arabic operator names. The carrier mapper returns brand names in
# English for the vast majority of territories; this overlay translates the
# brands without inventing an operator where the source has no data.
CARRIER_AR = {
    "MTN": "إم تي إن",
    "Syriatel": "سيرياتيل",
    "Wafa": "وافا",
    "stc": "إس تي سي",
    "STC": "إس تي سي",
    "Mobily": "موبايلي",
    "Zain": "زين",
    "Etisalat": "اتصالات",
    "e&": "إي آند",
    "du": "دو",
    "Ooredoo": "أوريدو",
    "Vodafone": "فودافون",
    "Orange": "أورنج",
    "Airtel": "إيرتل",
    "Jio": "جيو",
    "Vi": "في",
    "Idea": "آيديا",
    "BSNL": "بي إس إن إل",
    "Vivo": "فيفو",
    "Claro": "كلارو",
    "TIM": "تيم",
    "Telefonica": "تيليفونيكا",
    "Movistar": "موفيستار",
    "Entel": "إنتل",
    "T-Mobile": "تي موبايل",
    "AT&T": "إيه تي آند تي",
    "Verizon": "فيرايزون",
    "KDDI": "كيه دي دي آي",
    "NTT DoCoMo": "إن تي تي دوكومو",
    "SoftBank": "سوفت بانك",
    "Optus": "أوبتوس",
    "Telstra": "تيلسترا",
    "Kcell/Activ": "كيسيل/أكتيف",
    "Beeline": "بي لاين",
    "MegaFon": "ميغافون",
    "MTS": "إم تي إس",
    "Turkcell": "توركسل",
    "Vodacom": "فوداكوم",
    "MAFAB": "مافاب",
    "Safaricom": "سافاريكوم",
    "Globe": "غلوب",
    "SMART": "سمارت",
    "PLDT": "بي إل دي تي",
    "Singtel": "سينغتل",
    "StarHub": "ستار هب",
    "M1": "إم وان",
    "Maxis": "ماكسيس",
    "Celcom": "سيلكوم",
    "Digi": "ديجي",
    "True": "ترو",
    "AIS": "إيه آي إس",
    "Viettel": "فييتل",
    "Vinaphone": "فينافون",
    "MobiFone": "موبي فون",
    "Telkomsel": "تيلكومسل",
    "Indosat": "إندوسات",
    "XL": "إكس إل",
    "Smartfren": "سمارت فرين",
}

# The traditional fixed-line operator is not part of the carrier metadata. It
# is only shown for countries where a stable historical operator is documented
# in this curated overlay; elsewhere the app must say "unavailable" instead of
# guessing.
FIXED_OPERATOR = {
    "SY": {"ar": "الشركة السورية للاتصالات", "en": "Syrian Telecom"},
    "SA": {"ar": "شركة الاتصالات السعودية (stc)", "en": "stc"},
    "AE": {"ar": "اتصالات (Etisalat)", "en": "Etisalat"},
    "EG": {"ar": "المصرية للاتصالات", "en": "Telecom Egypt"},
    "JO": {"ar": "أورنج الأردن", "en": "Orange Jordan"},
    "LB": {"ar": "أوجيرو", "en": "Ogero"},
    "KW": {"ar": "زين", "en": "Zain"},
    "QA": {"ar": "أوريدو", "en": "Ooredoo"},
    "BH": {"ar": "بتلكو", "en": "Batelco"},
    "OM": {"ar": "عمانتل", "en": "Omantel"},
    "MA": {"ar": "اتصالات المغرب", "en": "Maroc Telecom"},
    "DZ": {"ar": "اتصالات الجزائر", "en": "Algérie Télécom"},
    "TN": {"ar": "اتصالات تونس", "en": "Tunisie Télécom"},
    "TR": {"ar": "ترك تيليكوم", "en": "Türk Telekom"},
    "DE": {"ar": "دويتشه تيليكوم", "en": "Deutsche Telekom"},
    "FR": {"ar": "أورنج", "en": "Orange"},
    "GB": {"ar": "بي تي", "en": "BT"},
    "US": {"ar": "شركات محلية متعددة", "en": "Multiple local carriers"},
}

# Curated emergency category labels. Numbers absent from this map are still
# listed as emergency numbers, but their service is shown as "طوارئ" rather
# than guessed. Reference data is never presented as officially verified.
EMERGENCY_CURATED: Dict[str, Dict[str, Tuple[str, str]]] = {
    "SY": {
        "112": ("الشرطة", "Police"),
        "110": ("الإسعاف", "Ambulance"),
        "113": ("الإطفاء", "Fire"),
    },
    "SA": {
        "999": ("الشرطة", "Police"),
        "997": ("الإسعاف", "Ambulance"),
        "998": ("الإطفاء", "Fire"),
        "911": ("طوارئ عامة", "General emergency"),
    },
    "AE": {
        "999": ("الشرطة", "Police"),
        "998": ("الإسعاف", "Ambulance"),
        "997": ("الإطفاء", "Fire"),
    },
    "EG": {
        "122": ("الشرطة", "Police"),
        "123": ("الإسعاف", "Ambulance"),
        "180": ("الإطفاء", "Fire"),
    },
    "JO": {
        "911": ("طوارئ عامة", "General emergency"),
        "199": ("الشرطة", "Police"),
        "9110": ("الإسعاف", "Ambulance"),
    },
    "LB": {
        "112": ("طوارئ عامة", "General emergency"),
        "999": ("طوارئ عامة", "General emergency"),
    },
    "KW": {"112": ("طوارئ عامة", "General emergency")},
    "QA": {"999": ("طوارئ عامة", "General emergency")},
    "BH": {"999": ("طوارئ عامة", "General emergency")},
    "OM": {"9999": ("طوارئ عامة", "General emergency")},
    "TR": {
        "112": ("طوارئ عامة", "General emergency"),
        "155": ("الشرطة", "Police"),
        "156": ("الدرك", "Gendarmerie"),
        "157": ("خفر السواحل", "Coast guard"),
    },
    "US": {"911": ("طوارئ عامة", "General emergency")},
    "GB": {
        "999": ("طوارئ عامة", "General emergency"),
        "112": ("طوارئ عامة", "General emergency"),
    },
    "DE": {
        "112": ("الإطفاء والإسعاف", "Fire and ambulance"),
        "110": ("الشرطة", "Police"),
    },
    "FR": {
        "112": ("طوارئ عامة", "General emergency"),
        "17": ("الشرطة", "Police"),
        "15": ("الإسعاف", "Ambulance"),
        "18": ("الإطفاء", "Fire"),
    },
    "TN": {
        "197": ("الشرطة", "Police"),
        "190": ("الإسعاف", "Ambulance"),
        "198": ("الإطفاء", "Fire"),
    },
    "MA": {
        "19": ("الشرطة", "Police"),
        "15": ("الإسعاف والإطفاء", "Ambulance and fire"),
    },
    "DZ": {
        "17": ("الشرطة", "Police"),
        "14": ("الإسعاف والإطفاء", "Ambulance and fire"),
    },
}

# EmergencyNumberAPI publishes exact service numbers for police, ambulance,
# fire, dispatch and traffic. It is community-maintained under the ODbL, so its
# entries stay at the REFERENCE verification level, exactly like the
# libphonenumber emergency data.
API_SERVICES: Dict[str, Tuple[str, str, str]] = {
    "Police": ("POLICE", "الشرطة", "Police"),
    "Ambulance": ("AMBULANCE", "الإسعاف", "Ambulance"),
    "Fire": ("FIRE", "الإطفاء", "Fire"),
    "Dispatch": ("DISPATCH", "غرفة العمليات", "Dispatch"),
    "Traffic": ("TRAFFIC", "المرور", "Traffic"),
}

# ShortNumberMetadata service categories. Premium-rate and SMS-service ranges
# are deliberately excluded: they are not useful directory entries for a
# citizen, resident or visitor, and many are commercial.
SHORT_SERVICE_TAGS: Dict[str, Tuple[str, str, str]] = {
    "expandedEmergency": ("EMERGENCY", "طوارئ إضافية", "Additional emergency"),
    "tollFree": ("TOLL_FREE", "رقم مجاني", "Toll-free"),
    "standardRate": ("STANDARD_RATE", "سعر قياسي", "Standard rate"),
    "carrierSpecific": ("CARRIER", "خدمة مشغّل", "Carrier service"),
    "shortCode": ("SHORT_CODE", "رمز خدمة قصير", "Short code"),
}

# Some upstream patterns describe millions of theoretical numbers (for example
# Chinese standard-rate ranges). Enumerating those would make the asset unusable
# and would not represent assigned service numbers. Emergency patterns are
# tiny, so they are expanded exactly; service ranges keep a bounded, clearly
# labelled sample plus the reference example number.
SHORT_ENUMERATION_CAPS: Dict[str, int] = {
    "emergency": 500,
    "expandedEmergency": 200,
    "tollFree": 50,
    "standardRate": 50,
    "carrierSpecific": 50,
    "shortCode": 50,
}

CATEGORY_ORDER: Dict[str, int] = {
    "GENERAL": 0,
    "POLICE": 1,
    "AMBULANCE": 2,
    "FIRE": 3,
    "DISPATCH": 4,
    "TRAFFIC": 5,
    "FIRE_AMBULANCE": 6,
    "GENDARMERIE": 7,
    "MARINE": 8,
    "MOUNTAIN": 9,
    "MULTI": 10,
    "EMERGENCY": 11,
    "TOLL_FREE": 12,
    "STANDARD_RATE": 13,
    "CARRIER": 14,
    "SHORT_CODE": 15,
}


@dataclass
class Plan:
    dfa: Dfa
    lengths: List[int]
    example: Optional[str]

    @property
    def total(self) -> int:
        return sum(count_by_length(self.dfa, length) for length in self.lengths)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def ensure_sources() -> dict:
    if not LOCK_FILE.exists():
        raise SystemExit(f"missing {LOCK_FILE}")
    lock = json.loads(LOCK_FILE.read_text(encoding="utf-8"))
    SOURCES.mkdir(parents=True, exist_ok=True)
    missing = []
    for name, entry in lock["sources"].items():
        path = SOURCES / name
        if not path.exists():
            missing.append((name, entry["url"]))
            continue
        actual = sha256(path)
        if actual != entry["sha256"]:
            raise SystemExit(
                f"source hash mismatch for {name}: expected {entry['sha256']}, got {actual}"
            )
    if missing:
        import urllib.request

        for name, url in missing:
            print(f"downloading {name} from {url}")
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            path = SOURCES / name
            path.write_bytes(data)
            expected = lock["sources"][name]["sha256"]
            actual = sha256(path)
            if actual != expected:
                path.unlink(missing_ok=True)
                raise SystemExit(
                    f"downloaded hash mismatch for {name}: expected {expected}, got {actual}"
                )
    return lock


def load_metadata(path: Path) -> Dict[str, ET.Element]:
    root = ET.parse(path).getroot()
    territories = {}
    for territory in root.find("territories").findall("territory"):
        iso = territory.get("id")
        if iso and iso != "001":
            territories[iso] = territory
    return territories


def parse_lengths(element: Optional[ET.Element], fallback: Sequence[int]) -> List[int]:
    if element is None:
        return list(fallback)
    value = element.get("national")
    if not value:
        return list(fallback)
    lengths: Set[int] = set()
    for part in value.split(","):
        part = part.strip()
        if not part:
            continue
        if part.startswith("[") and part.endswith("]"):
            bounds = part[1:-1]
            if "-" in bounds:
                start, end = bounds.split("-", 1)
                lengths.update(range(int(start), int(end) + 1))
            else:
                lengths.add(int(bounds))
        else:
            lengths.add(int(part))
    return sorted(lengths) or list(fallback)


def build_plan(territory: ET.Element, tag: str, country_code: str) -> Optional[Plan]:
    element = territory.find(tag)
    if element is None:
        return None
    pattern_element = element.find("nationalNumberPattern")
    if pattern_element is None or not pattern_element.text:
        return None
    max_e164 = 15 - len(country_code)
    lengths = parse_lengths(element.find("possibleLengths"), range(1, 16))
    lengths = [length for length in lengths if 1 <= length <= max_e164]
    if not lengths:
        return None
    pattern = pattern_element.text
    for length in lengths:
        if length not in range(1, 16):
            raise SystemExit(f"invalid length {length} for {territory.get('id')} {tag}")
    dfa = compile_pattern(pattern, min(lengths), max(lengths))
    # Intersect with the declared possible lengths by rebuilding the length
    # product: compile_pattern already restricts to the min/max, so masks are
    # applied by dropping accepting states outside the declared set.
    if lengths != list(range(min(lengths), max(lengths) + 1)):
        dfa = restrict_lengths(dfa, lengths)
    return Plan(dfa, lengths, element.findtext("exampleNumber"))


def restrict_lengths(dfa: Dfa, lengths: Sequence[int]) -> Dfa:
    """Keep only accepting states whose length is in *lengths*.

    Every state of a length-bounded DFA corresponds to exactly one length, so
    the length can be recovered by a BFS from the start state.
    """
    from collections import deque

    depth = {dfa.start: 0}
    queue = deque([dfa.start])
    while queue:
        state = queue.popleft()
        for target in dfa.transitions[state].values():
            if target not in depth:
                depth[target] = depth[state] + 1
                queue.append(target)
    allowed = set(lengths)
    accepting = {state for state in dfa.accepting if depth.get(state) in allowed}
    return Dfa(dfa.start, dfa.transitions, accepting)


def intersection_stats(
    first: Plan, second: Plan, country_code: str
) -> Tuple[int, List[int]]:
    """Exact count and per-length counts of numbers accepted by both plans.

    This is a path count over the product automaton, not a state count: several
    different digit strings can reach the same pair of states, and each string
    is one number.
    """
    max_length = 15 - len(country_code)
    start = (first.dfa.start, second.dfa.start)
    depth: Dict[Tuple[int, int], int] = {start: 0}
    order: List[Tuple[int, int]] = [start]
    edges: Dict[Tuple[int, int], List[Tuple[int, int]]] = {}
    index = 0
    while index < len(order):
        a, b = order[index]
        index += 1
        level = depth[(a, b)]
        targets: List[Tuple[int, int]] = []
        if level < max_length:
            for digit in "0123456789":
                target_a = first.dfa.transitions[a].get(digit)
                target_b = second.dfa.transitions[b].get(digit)
                if target_a is not None and target_b is not None:
                    key = (target_a, target_b)
                    if key not in depth:
                        depth[key] = level + 1
                        order.append(key)
                    targets.append(key)
        edges[(a, b)] = targets

    ways: Dict[Tuple[int, int], int] = {start: 1}
    by_length = [0] * (max_length + 1)
    for state in order:
        count = ways.get(state, 0)
        if count == 0:
            continue
        a, b = state
        level = depth[state]
        if a in first.dfa.accepting and b in second.dfa.accepting:
            by_length[level] += count
        for target in edges.get(state, ()):
            ways[target] = ways.get(target, 0) + count
    return sum(by_length), by_length[1:]


def babel_names() -> Tuple[Dict[str, str], Dict[str, str]]:
    ar = Locale.parse("ar")
    en = Locale.parse("en")
    return dict(ar.territories), dict(en.territories)


def flag_for(iso: str) -> str:
    return "".join(chr(0x1F1E6 + ord(ch) - ord("A")) for ch in iso.upper())


def load_overlay(name: str, default):
    path = OVERLAYS / name
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def build_countries(territories: Dict[str, ET.Element], plans: Dict[str, Dict[str, Plan]]):
    ar_names, en_names = babel_names()
    iso_codes = {country.alpha_2 for country in pycountry.countries}
    all_codes = sorted(iso_codes | set(territories) | set(EXTRA_TERRITORIES))
    calling_groups: Dict[str, List[str]] = {}
    for code in all_codes:
        territory = territories.get(code)
        calling = (
            EXTRA_TERRITORIES[code][2]
            if code in EXTRA_TERRITORIES
            else (territory.get("countryCode") if territory is not None else None)
        ) or MISSING_CALLING_CODES.get(code)
        if calling:
            calling_groups.setdefault(calling, []).append(code)
    countries = []
    for iso in all_codes:
        territory = territories.get(iso)
        if iso in EXTRA_TERRITORIES:
            en, ar, calling = EXTRA_TERRITORIES[iso]
        else:
            en = en_names.get(iso) or iso
            ar = ar_names.get(iso) or en
            calling = territory.get("countryCode") if territory is not None else None
        if not calling:
            calling = MISSING_CALLING_CODES.get(iso)
        if not calling:
            raise SystemExit(f"no calling code for {iso}")
        mobile = plans.get(iso, {}).get("MOBILE")
        fixed = plans.get(iso, {}).get("FIXED")
        mobile_count = mobile.total if mobile else 0
        fixed_count = fixed.total if fixed else 0
        overlap, overlap_lengths = (0, [])
        if mobile and fixed:
            overlap, overlap_lengths = intersection_stats(mobile, fixed, calling)
        both_count = mobile_count + fixed_count - overlap
        support = (
            "FULL"
            if mobile and fixed
            else "FIXED_ONLY"
            if fixed
            else "MOBILE_ONLY"
            if mobile
            else "UNAVAILABLE"
        )
        main_country = None
        if territory is not None and territory.get("mainCountryForCode") == "true":
            main_country = iso
        if main_country is None:
            for candidate in calling_groups.get(calling, []):
                candidate_territory = territories.get(candidate)
                if candidate_territory is not None and candidate_territory.get("mainCountryForCode") == "true":
                    main_country = candidate
                    break
        countries.append(
            {
                "iso2": iso,
                "nameAr": ar,
                "nameEn": en,
                "callingCode": calling,
                "nationalPrefix": territory.get("nationalPrefix") if territory is not None else ("0" if iso == "XK" else None),
                "flag": flag_for(iso),
                "flagFallback": iso in {"AC", "TA", "XK"},
                "support": support,
                "hasMobile": mobile is not None,
                "hasFixed": fixed is not None,
                "mobileLengths": mobile.lengths if mobile else [],
                "fixedLengths": fixed.lengths if fixed else [],
                "mobileCount": mobile_count,
                "fixedCount": fixed_count,
                "bothCount": both_count,
                "overlapCount": overlap,
                "exampleMobile": mobile.example if mobile else None,
                "exampleFixed": fixed.example if fixed else None,
                "planVersion": PLAN_VERSION,
                "mainCountry": main_country,
                "sharedWith": [
                    other
                    for other in calling_groups.get(calling, [])
                    if other != iso
                ],
            }
        )
    return countries


def build_emergency(short_territories: Dict[str, ET.Element], countries: Dict[str, dict]):
    """Build the offline emergency and service directory.

    Three sources are merged per (country, number):

    * ``emergency`` from ShortNumberMetadata, expanded exactly;
    * police/ambulance/fire/dispatch/traffic numbers from EmergencyNumberAPI;
    * useful service categories (expanded emergency, toll-free, standard-rate,
      carrier-specific and short codes), capped when the upstream pattern
      describes a very large theoretical range.

    The cap is explicit and every affected entry carries a note telling the
    user that the source contains a wider range. No number is invented: every
    entry comes from one of the pinned upstream files.
    """
    records: Dict[Tuple[str, str], dict] = {}

    def ensure(iso: str, number: str) -> dict:
        key = (iso, number)
        record = records.get(key)
        if record is None:
            record = {
                "iso2": iso,
                "number": number,
                "curated": None,
                "api_services": [],
                "short_emergency": False,
                "short_expanded": False,
                "short_service": None,
                "sources": set(),
                "api_local_only": False,
                "short_truncated": False,
            }
            records[key] = record
        return record

    for iso, territory in sorted(short_territories.items()):
        if iso not in countries:
            continue
        curated = EMERGENCY_CURATED.get(iso, {})

        emergency = territory.find("emergency")
        if emergency is not None:
            numbers, _ = expand_short_pattern_capped(
                emergency,
                SHORT_ENUMERATION_CAPS["emergency"],
            )
            for number in numbers:
                record = ensure(iso, number)
                record["short_emergency"] = True
                record["sources"].add("short")
                if number in curated:
                    ar_label, en_label = curated[number]
                    record["curated"] = (
                        category_key(en_label),
                        ar_label,
                        en_label,
                    )

        for tag, service in SHORT_SERVICE_TAGS.items():
            element = territory.find(tag)
            if element is None:
                continue
            numbers, truncated = expand_short_pattern_capped(
                element,
                SHORT_ENUMERATION_CAPS[tag],
            )
            for number in numbers:
                record = ensure(iso, number)
                record["sources"].add("short")
                record["short_truncated"] = record["short_truncated"] or truncated
                if tag == "expandedEmergency":
                    record["short_expanded"] = True
                elif record["short_service"] is None:
                    record["short_service"] = service

    api_path = SOURCES / "EmergencyNumberAPI.json"
    api = json.loads(api_path.read_text(encoding="utf-8"))
    for item in api.get("data", []):
        iso = item.get("Country", {}).get("ISOCode")
        if not iso or iso not in countries:
            continue
        local_only = bool(item.get("LocalOnly"))
        for service_name, service in API_SERVICES.items():
            block = item.get(service_name) or {}
            if not isinstance(block, dict):
                continue
            for values in block.values():
                if not isinstance(values, list):
                    continue
                for raw in values:
                    for number in clean_api_number(raw):
                        record = ensure(iso, number)
                        record["sources"].add("api")
                        record["api_local_only"] = (
                            record["api_local_only"] or local_only
                        )
                        if service not in record["api_services"]:
                            record["api_services"].append(service)

        # The dataset flags EU/EEA members where 112 is the common emergency
        # number. Add it only when the other sources did not already list it.
        if item.get("Member_112") and (iso, "112") not in records:
            record = ensure(iso, "112")
            record["sources"].add("api")
            record["api_local_only"] = local_only
            record["api_services"].append(
                ("GENERAL", "طوارئ عامة", "General emergency")
            )

    entries = []
    for (iso, number), record in sorted(records.items()):
        country = countries[iso]
        api_services = record["api_services"]
        if record["curated"] is not None:
            category, label_ar, label_en = record["curated"]
        elif api_services:
            if len(api_services) == 1:
                category, label_ar, label_en = api_services[0]
            else:
                category = "MULTI"
                label_ar = arabic_join([service[1] for service in api_services])
                label_en = " / ".join(service[2] for service in api_services)
        elif record["short_emergency"]:
            category, label_ar, label_en = "GENERAL", "طوارئ", "Emergency"
        elif record["short_expanded"]:
            category, label_ar, label_en = SHORT_SERVICE_TAGS["expandedEmergency"]
        elif record["short_service"] is not None:
            category, label_ar, label_en = record["short_service"]
        else:
            continue

        sources = record["sources"]
        has_api = "api" in sources
        has_short = "short" in sources
        source = (
            "libphonenumber ShortNumberMetadata "
            + METADATA_VERSION
            + " + EmergencyNumberAPI"
            if has_api and has_short
            else "EmergencyNumberAPI"
            if has_api
            else "libphonenumber ShortNumberMetadata " + METADATA_VERSION
        )
        source_url = (
            "https://github.com/EmergencyNumbersAPI/data"
            if has_api
            else "https://github.com/google/libphonenumber"
        )

        if has_api and has_short:
            note = (
                "بيانات مرجعية من libphonenumber وEmergencyNumberAPI (ODbL)؛ "
                "لم تُؤكد رسمياً. تحقق محلياً قبل الاستخدام."
            )
        elif has_api:
            note = (
                "بيانات مجتمعية من EmergencyNumberAPI بترخيص ODbL؛ "
                "لم تُؤكد رسمياً. تحقق محلياً قبل الاستخدام."
            )
        elif category in {"GENERAL", "POLICE", "AMBULANCE", "FIRE", "DISPATCH",
                          "TRAFFIC", "FIRE_AMBULANCE", "GENDARMERIE", "MARINE",
                          "MOUNTAIN", "MULTI"}:
            note = "بيانات مرجعية آلية، لم تُؤكد رسمياً من جهة وطنية."
        elif category == "EMERGENCY":
            note = "رقم طوارئ موسع من البيانات المرجعية؛ لم يُؤكد رسمياً."
        elif category == "TOLL_FREE":
            note = "رقم مجاني عادةً من الخطوط المحلية؛ قد تختلف الشروط."
        elif category == "STANDARD_RATE":
            note = "قد تُطبق تعرفة قياسية؛ تحقق من المشغّل."
        elif category == "CARRIER":
            note = "خدمة خاصة بمشغّل؛ قد يختلف الرقم بين المشغّلين."
        else:
            note = "رمز خدمة قصير؛ قد يختلف حسب المشغّل والخدمة."

        if record["curated"] is not None and api_services:
            services_ar = "، ".join(service[1] for service in api_services)
            note = f"تشمل: {services_ar}. " + note
        if record["api_local_only"] and has_api:
            note += " قد يعمل محلياً فقط وفق المصدر."
        if record["short_truncated"]:
            note += (
                " هذه قائمة مختارة من نطاق أوسع في المصدر؛ "
                "راجع المصدر للقائمة الكاملة."
            )

        entries.append(
            {
                "iso2": iso,
                "number": number,
                "category": category,
                "labelAr": label_ar,
                "labelEn": label_en,
                "countryAr": country["nameAr"],
                "countryEn": country["nameEn"],
                "verification": "REFERENCE",
                "source": source,
                "sourceUrl": source_url,
                "checkedAt": None,
                "noteAr": note,
            }
        )

    entries.sort(
        key=lambda entry: (
            entry["iso2"],
            CATEGORY_ORDER.get(entry["category"], 99),
            len(entry["number"]),
            entry["number"],
        )
    )
    validate_emergency(entries, countries)
    return entries


def clean_api_number(raw) -> List[str]:
    """Return exact numbers from one EmergencyNumberAPI value.

    The dataset mostly contains 2-8 digit strings, but a handful of records use
    spaces, a unicode dash range, a compact suffix range or a prose note. Prose
    is ignored; numeric ranges are expanded with a hard bound so one malformed
    record cannot explode the asset.
    """
    if raw is None:
        return []
    value = str(raw).strip()
    if not value:
        return []
    value = (
        value.replace("\u2010", "-")
        .replace("\u2011", "-")
        .replace("\u2012", "-")
        .replace("\u2013", "-")
        .replace("\u2014", "-")
    )
    value = re.sub(r"\s+", "", value)
    if not re.fullmatch(r"[0-9+\-]+", value):
        return []
    value = value.lstrip("+")
    if re.fullmatch(r"[0-9]{2,15}", value):
        return [value]
    match = re.fullmatch(r"([0-9]+)-([0-9]+)", value)
    if match is None:
        return []
    start, end = match.groups()
    if len(end) < len(start):
        end = start[: len(start) - len(end)] + end
    try:
        first, last = int(start), int(end)
    except ValueError:
        return []
    if last < first or last - first > 500:
        return []
    width = len(start)
    return [str(number).zfill(width) for number in range(first, last + 1)]


def arabic_join(labels: Sequence[str]) -> str:
    """Join Arabic service labels with the conjunction و.

    The first term of an idafa is indefinite, so the definite article is
    removed from every word, including words after the conjunction.
    """
    if len(labels) == 1:
        return labels[0]

    def indefinite(word: str) -> str:
        conjunction = "و" if word.startswith("و") else ""
        core = word[1:] if conjunction else word
        return conjunction + (core[2:] if core.startswith("ال") else core)

    parts = [
        " ".join(indefinite(word) for word in label.split())
        for label in labels
    ]
    return " و".join(parts)


def expand_short_pattern_capped(
    element: ET.Element,
    cap: int,
) -> Tuple[List[str], bool]:
    """Expand a short-number pattern up to *cap* exact values.

    Returns the values and whether the pattern contains more numbers than the
    cap. The reference ``exampleNumber`` is always included first, so a capped
    range still contains one documented assigned number.
    """
    from dfa import compile_pattern, count_by_length, enumerate_prefix

    pattern = element.findtext("nationalNumberPattern")
    if not pattern:
        return [], False
    lengths = parse_lengths(element.find("possibleLengths"), range(1, 9))
    dfa = compile_pattern(pattern, min(lengths), max(lengths))
    total = sum(count_by_length(dfa, length) for length in lengths)
    # Enumerate a little beyond the cap because length filtering can discard
    # values, then keep the first *cap* accepted values in numeric order.
    values = enumerate_prefix(dfa, "", max(cap * 4, cap + 100))
    allowed = set(lengths)
    result = [value for value in values if len(value) in allowed][:cap]
    example = (element.findtext("exampleNumber") or "").strip()
    if example and example not in result:
        result.insert(0, example)
        result = result[:cap]
    return result, total > cap


def validate_emergency(entries: List[dict], countries: Dict[str, dict]) -> None:
    valid_categories = set(CATEGORY_ORDER)
    seen = set()
    for entry in entries:
        key = (entry["iso2"], entry["number"])
        if key in seen:
            raise SystemExit(f"duplicate emergency entry: {key}")
        seen.add(key)
        if entry["iso2"] not in countries:
            raise SystemExit(f"emergency entry has unknown country: {entry['iso2']}")
        if not re.fullmatch(r"[0-9]{2,15}", entry["number"]):
            raise SystemExit(f"invalid emergency number: {key}")
        if entry["category"] not in valid_categories:
            raise SystemExit(f"invalid emergency category: {entry['category']}")
        if not entry["labelAr"] or not entry["labelEn"]:
            raise SystemExit(f"emergency entry has no label: {key}")
        if entry["verification"] != "REFERENCE":
            raise SystemExit(f"emergency entry is not REFERENCE: {key}")
        if not entry["source"]:
            raise SystemExit(f"emergency entry has no source: {key}")
    if len(entries) < 1000:
        raise SystemExit(f"emergency directory is unexpectedly small: {len(entries)}")
    if len({entry["iso2"] for entry in entries}) < 200:
        raise SystemExit("emergency directory covers fewer than 200 territories")


def category_key(label: str) -> str:
    mapping = {
        "Police": "POLICE",
        "Ambulance": "AMBULANCE",
        "Fire": "FIRE",
        "General emergency": "GENERAL",
        "Fire and ambulance": "FIRE_AMBULANCE",
        "Ambulance and fire": "FIRE_AMBULANCE",
        "Gendarmerie": "GENDARMERIE",
        "Coast guard": "MARINE",
        "Mountain rescue": "MOUNTAIN",
    }
    return mapping.get(label, "GENERAL")


def expand_short_pattern(pattern: str, lengths: Sequence[int], limit: int) -> List[str]:
    from dfa import compile_pattern, enumerate_prefix

    minimum, maximum = min(lengths), max(lengths)
    dfa = compile_pattern(pattern, minimum, maximum)
    # Enumerating from the root is tiny for short-code patterns.
    results = enumerate_prefix(dfa, "", limit)
    allowed = set(lengths)
    return [value for value in results if len(value) in allowed]


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    path.write_text(text + "\n", encoding="utf-8")


def generate(target: Path) -> None:
    lock = ensure_sources()
    territories = load_metadata(SOURCES / "PhoneNumberMetadata.xml")
    short_territories = load_metadata(SOURCES / "ShortNumberMetadata.xml")
    plans: Dict[str, Dict[str, Plan]] = {}
    countries_by_iso: Dict[str, dict] = {}
    plan_dir = target / "plans"
    plan_dir.mkdir(parents=True, exist_ok=True)

    for iso, territory in sorted(territories.items()):
        country_code = territory.get("countryCode")
        if not country_code:
            continue
        types: Dict[str, Plan] = {}
        mobile = build_plan(territory, "mobile", country_code)
        fixed = build_plan(territory, "fixedLine", country_code)
        if mobile:
            types["MOBILE"] = mobile
        if fixed:
            types["FIXED"] = fixed
        if not types:
            continue
        plans[iso] = types
        for line_type, plan in types.items():
            write_json(
                plan_dir / f"{iso}_{line_type}.json",
                to_json(plan.dfa, iso, line_type, PLAN_VERSION),
            )

    countries = build_countries(territories, plans)
    countries_by_iso = {country["iso2"]: country for country in countries}

    # Syria's approved mobile rules are stricter than the upstream metadata:
    # the app keeps 091/092/093/094/095/096/098/099 and rejects 090/097.
    sy_territory = territories.get("SY")
    if sy_territory is not None:
        sy_mobile_dfa = compile_pattern("9[1-68-9]\\d{7}", 9, 9)
        yemen_plan = Plan(sy_mobile_dfa, [9], "944567890")
        plans.setdefault("SY", {})["MOBILE"] = yemen_plan
        write_json(
            plan_dir / "SY_MOBILE.json",
            to_json(sy_mobile_dfa, "SY", "MOBILE", PLAN_VERSION),
        )
        sy_fixed = plans["SY"].get("FIXED")
        overlap, _ = intersection_stats(yemen_plan, sy_fixed, "963") if sy_fixed else (0, [])
        sy_country = countries_by_iso["SY"]
        sy_country.update(
            {
                "mobileCount": yemen_plan.total,
                "bothCount": yemen_plan.total + (sy_fixed.total if sy_fixed else 0) - overlap,
                "overlapCount": overlap,
                "exampleMobile": "944567890",
            }
        )

    write_json(target / "countries.json", {"version": PLAN_VERSION, "countries": countries})
    emergency_entries = build_emergency(short_territories, countries_by_iso)
    write_json(target / "emergency.json", {
        "version": PLAN_VERSION,
        "generatedFrom": (
            "libphonenumber ShortNumberMetadata "
            + METADATA_VERSION
            + " + EmergencyNumberAPI"
        ),
        "count": len(emergency_entries),
        "countryCount": len({entry["iso2"] for entry in emergency_entries}),
        "entries": emergency_entries,
    })

    overlays = {
        "geo_ar.json": load_overlay("geo_ar.json", {"version": 1, "descriptions": {}, "fixedOperators": {}}),
        "carriers_ar.json": {
            "version": 1,
            "source": "NumX curated brand overlay",
            "carriers": {**CARRIER_AR, **load_overlay("carriers_ar.json", {}).get("carriers", {})},
            "fixedOperators": {
                iso: value for iso, value in FIXED_OPERATOR.items()
            },
        },
    }
    for name, value in overlays.items():
        write_json(target / name, value)

    sources_asset = {
        "metadataVersion": METADATA_VERSION,
        "planVersion": PLAN_VERSION,
        "generatedAt": lock.get("generatedAt"),
        "sources": [
            {
                "name": name,
                "version": entry.get("version"),
                "url": entry["url"],
                "sha256": entry["sha256"],
                "license": entry.get("license"),
            }
            for name, entry in sorted(lock["sources"].items())
        ],
        "notes": [
            "libphonenumber metadata is licensed under Apache-2.0.",
            "Geocoder and carrier data are shipped inside the libphonenumber Android libraries.",
            "Emergency entries merge libphonenumber ShortNumberMetadata with EmergencyNumberAPI (ODbL 1.0).",
            "Emergency entries are machine-readable reference data, not official verification.",
            "Arabic geographic names come from a curated NumX overlay; original English descriptions are shown when no curated name exists.",
        ],
    }
    write_json(target / "sources.json", sources_asset)

    manifest_files = []
    for path in sorted(target.rglob("*.json")):
        if path.name == "manifest.json":
            continue
        manifest_files.append({"path": str(path.relative_to(target)), "sha256": sha256(path)})
    write_json(
        target / "manifest.json",
        {
            "fingerprint": fingerprint(lock),
            "toolVersion": TOOL_VERSION,
            "metadataVersion": METADATA_VERSION,
            "files": manifest_files,
        },
    )


def fingerprint(lock: dict) -> str:
    digest = hashlib.sha256()
    digest.update(TOOL_VERSION.encode())
    digest.update(METADATA_VERSION.encode())
    digest.update(LOCK_FILE.read_bytes())
    for path in sorted(HERE.glob("*.py")):
        digest.update(path.read_bytes())
    for path in sorted(OVERLAYS.glob("*.json")) if OVERLAYS.exists() else []:
        digest.update(path.read_bytes())
    return digest.hexdigest()


def check() -> int:
    lock = ensure_sources()
    if not ASSETS.exists():
        print("metadata assets are missing", file=sys.stderr)
        return 1
    manifest_path = ASSETS / "manifest.json"
    if not manifest_path.exists():
        print("metadata manifest is missing", file=sys.stderr)
        return 1
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("fingerprint") != fingerprint(lock):
        print(
            "metadata assets are stale: sources/tool/overlays changed. "
            "Run tools/metadata/build_metadata.py.",
            file=sys.stderr,
        )
        return 1
    for entry in manifest["files"]:
        path = ASSETS / entry["path"]
        if not path.exists() or sha256(path) != entry["sha256"]:
            print(f"metadata asset mismatch: {entry['path']}", file=sys.stderr)
            return 1
    print(f"metadata assets OK ({len(manifest['files'])} files)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        return check()
    ASSETS.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        target = Path(tmp) / "metadata"
        generate(target)
        # Replace atomically per file, then drop stale plan files.
        if (ASSETS / "plans").exists():
            shutil.rmtree(ASSETS / "plans")
        for path in sorted(target.rglob("*")):
            if path.is_file():
                destination = ASSETS / path.relative_to(target)
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, destination)
    print(f"wrote metadata assets to {ASSETS}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
