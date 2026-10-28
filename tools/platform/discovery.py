"""Bounded source discovery.

Two discoveries are implemented:

* ITU National Numbering Plan pages are parsed for links to the actual
  Word/PDF documents.  Only links are recorded; documents are not downloaded.
* A small Wikidata organization sample is discovered through allowed Wikipedia
  category pages, then fetched from the allowed ``Special:EntityData`` JSON
  endpoint.

No search engine scraping, no bulk crawling, and no blocked endpoint is used.
"""

from __future__ import annotations

import json
import re
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timezone
from typing import Any, Iterable, Mapping

from bs4 import BeautifulSoup

from . import db, http, paths

WIKIDATA_COUNTRIES = [
    ("SY", "Syria"),
    ("JO", "Jordan"),
    ("LB", "Lebanon"),
    ("EG", "Egypt"),
    ("SA", "Saudi Arabia"),
    ("AE", "United Arab Emirates"),
    ("MA", "Morocco"),
    ("DZ", "Algeria"),
    ("TN", "Tunisia"),
    ("IQ", "Iraq"),
    ("QA", "Qatar"),
    ("KW", "Kuwait"),
]

CATEGORY_TEMPLATES = [
    ("Telecommunications companies of {name}", "business"),
    ("Government agencies of {name}", "government"),
]


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _client() -> http.HttpClient:
    return http.HttpClient(
        cache_dir=paths.IMPORT_DIR / "http-cache",
        timeout=20.0,
        retries=1,
        min_interval=0.35,
    )


def _pick_document(links: Iterable[str]) -> str | None:
    candidates = [link for link in links if re.search(r"\.(docx?|pdf|xlsx?|zip)(\?|$)", link, re.I)]
    if not candidates:
        return None
    # Prefer the English Word document, then the English PDF.
    for token in ("MSWE.docx", "MSWE.doc", "PDFE.pdf"):
        for link in candidates:
            if token in link:
                return link
    return candidates[0]


def discover_itu_documents(
    connection: Any,
    *,
    limit: int | None = None,
    workers: int = 4,
) -> dict[str, Any]:
    rows = [
        dict(row)
        for row in connection.execute(
            "SELECT * FROM source_registry WHERE source_id LIKE 'itu-nnp-%' ORDER BY source_id"
        )
    ]
    if limit is not None:
        rows = rows[:limit]
    client = _client()

    def fetch(row: Mapping[str, Any]) -> tuple[Mapping[str, Any], list[str], str | None]:
        result = client.fetch(row["source_url"], max_bytes=300_000, use_cache=True, check_robots=True)
        if not result.ok:
            return row, [], result.error
        soup = BeautifulSoup(result.text, "lxml")
        links: list[str] = []
        for anchor in soup.find_all("a", href=True):
            href = urllib.parse.urljoin(result.final_url, anchor["href"])
            if "itu.int/dms_pub/" in href:
                links.append(href)
        return row, sorted(set(links)), None

    documents: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(fetch, row): row for row in rows}
        for future in as_completed(futures):
            row, links, error = future.result()
            chosen = _pick_document(links)
            documents.append(
                {
                    "sourceId": row["source_id"],
                    "countryIso2": row.get("country_iso2"),
                    "pageUrl": row["source_url"],
                    "documentLinks": links,
                    "preferredDocument": chosen,
                    "error": error,
                }
            )
            if chosen:
                with connection:
                    connection.execute(
                        "UPDATE source_registry SET download_url=?, updated_at=? WHERE source_id=?",
                        (chosen, utc_now(), row["source_id"]),
                    )
    documents.sort(key=lambda item: item["sourceId"])
    payload = {
        "generatedAt": utc_now(),
        "pagesChecked": len(rows),
        "pagesWithDocuments": sum(1 for item in documents if item["documentLinks"]),
        "documents": documents,
    }
    paths.ensure_dirs()
    output = paths.CATALOG_DIR / "itu-documents.json"
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    with connection:
        db.audit(
            connection,
            "discovery.itu_documents",
            details={"pages": len(rows), "with_documents": payload["pagesWithDocuments"]},
        )
    return {"output": str(output), **{key: payload[key] for key in ("pagesChecked", "pagesWithDocuments")}}


def _category_url(name: str) -> str:
    return "https://en.wikipedia.org/wiki/Category:" + urllib.parse.quote(name.replace(" ", "_"))


def _article_links(html: str, limit: int) -> list[str]:
    soup = BeautifulSoup(html, "lxml")
    links: list[str] = []
    container = soup.find(id="mw-pages") or soup
    for anchor in container.find_all("a", href=True):
        href = anchor["href"]
        if not href.startswith("/wiki/"):
            continue
        title = href[len("/wiki/") :]
        if ":" in title or title.startswith("Category:"):
            continue
        url = "https://en.wikipedia.org" + href.split("#", 1)[0]
        if url not in links:
            links.append(url)
        if len(links) >= limit:
            break
    return links


def _entity_id_from_html(html: str) -> str | None:
    match = re.search(r'"wgWikibaseItemId":"(Q\d+)"', html)
    return match.group(1) if match else None


def _entity_value(claim: Mapping[str, Any]) -> Any:
    snak = claim.get("mainsnak", {})
    if snak.get("snaktype") != "value":
        return None
    value = snak.get("datavalue", {}).get("value")
    if isinstance(value, Mapping) and "text" in value:
        return value["text"]
    return value


def _entity_claims(entity: Mapping[str, Any], property_id: str) -> list[Any]:
    values = []
    for claim in entity.get("claims", {}).get(property_id, []):
        if claim.get("rank") == "deprecated":
            continue
        value = _entity_value(claim)
        if value is not None:
            values.append(value)
    return values


def _parse_entity(qid: str, data: Mapping[str, Any], *, country_iso2: str, category: str, wikipedia_url: str) -> list[dict[str, Any]]:
    entity = data.get("entities", {}).get(qid, {})
    labels = entity.get("labels", {})
    name = (
        labels.get("en", {}).get("value")
        or labels.get("ar", {}).get("value")
        or labels.get("mul", {}).get("value")
    )
    phones = [str(value) for value in _entity_claims(entity, "P1329") if value]
    websites = [str(value) for value in _entity_claims(entity, "P856") if value]
    coordinates = _entity_claims(entity, "P625")
    address = None
    addresses = _entity_claims(entity, "P6375")
    if addresses:
        address = str(addresses[0])
    latitude = longitude = None
    if coordinates and isinstance(coordinates[0], Mapping):
        latitude = coordinates[0].get("latitude")
        longitude = coordinates[0].get("longitude")
    entries = []
    for phone in phones:
        entries.append(
            {
                "qid": qid,
                "name": name or qid,
                "phone": phone,
                "website": websites[0] if websites else None,
                "address": address,
                "latitude": latitude,
                "longitude": longitude,
                "country_iso2": country_iso2,
                "category": category,
                "wikipedia_url": wikipedia_url,
                "entity_url": f"https://www.wikidata.org/wiki/Special:EntityData/{qid}.json",
                "modified": entity.get("modified"),
                "instance_of": _entity_claims(entity, "P31"),
            }
        )
    return entries


def discover_wikidata(
    connection: Any,
    *,
    countries: Iterable[tuple[str, str]] | None = None,
    max_articles_per_category: int = 4,
    workers: int = 4,
) -> dict[str, Any]:
    countries = list(countries or WIKIDATA_COUNTRIES)
    client = _client()

    def fetch_category(item: tuple[str, str, str, str]) -> tuple[str, str, str, list[str], str | None]:
        iso2, country_name, template, category = item
        url = _category_url(template.format(name=country_name))
        result = client.fetch(url, max_bytes=500_000, use_cache=True, check_robots=True)
        if not result.ok:
            return iso2, category, url, [], result.error
        return iso2, category, url, _article_links(result.text, max_articles_per_category), None

    category_tasks = [
        (iso2, name, template, category)
        for iso2, name in countries
        for template, category in CATEGORY_TEMPLATES
    ]
    articles: list[tuple[str, str, str, str]] = []
    category_report = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(fetch_category, item): item for item in category_tasks}
        for future in as_completed(futures):
            iso2, category, url, links, error = future.result()
            category_report.append(
                {"countryIso2": iso2, "category": category, "url": url, "articles": links, "error": error}
            )
            for link in links:
                articles.append((iso2, category, link, url))

    def fetch_article(item: tuple[str, str, str, str]) -> tuple[str, str, str, str, str | None, str | None]:
        iso2, category, article_url, category_url = item
        page = client.fetch(article_url, max_bytes=800_000, use_cache=True, check_robots=True)
        if not page.ok:
            return iso2, category, article_url, category_url, None, page.error
        qid = _entity_id_from_html(page.text)
        return iso2, category, article_url, category_url, qid, None

    entity_entries: list[dict[str, Any]] = []
    article_report = []
    qids: dict[str, tuple[str, str, str]] = {}
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(fetch_article, item): item for item in articles}
        for future in as_completed(futures):
            iso2, category, article_url, category_url, qid, error = future.result()
            article_report.append(
                {
                    "countryIso2": iso2,
                    "category": category,
                    "article": article_url,
                    "qid": qid,
                    "error": error,
                }
            )
            if qid:
                qids.setdefault(qid, (iso2, category, article_url))

    def fetch_entity(item: tuple[str, tuple[str, str, str]]) -> tuple[str, str, str, list[dict[str, Any]], str | None]:
        qid, (iso2, category, article_url) = item
        url = f"https://www.wikidata.org/wiki/Special:EntityData/{qid}.json"
        result = client.fetch(
            url,
            max_bytes=1_500_000,
            use_cache=True,
            check_robots=True,
            accept="application/json",
        )
        if not result.ok:
            return qid, iso2, category, [], result.error
        try:
            data = json.loads(result.text)
        except json.JSONDecodeError as exc:
            return qid, iso2, category, [], f"JSON error: {exc}"
        entries = _parse_entity(
            qid,
            data,
            country_iso2=iso2,
            category=category,
            wikipedia_url=article_url,
        )
        return qid, iso2, category, entries, None

    entity_errors = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        futures = {pool.submit(fetch_entity, item): item for item in qids.items()}
        for future in as_completed(futures):
            qid, iso2, category, entries, error = future.result()
            if error:
                entity_errors.append({"qid": qid, "error": error})
            entity_entries.extend(entries)

    deduped: dict[tuple[str, str], dict[str, Any]] = {}
    for entry in entity_entries:
        deduped[(entry["qid"], entry["phone"])] = entry
    entries = sorted(deduped.values(), key=lambda item: (item["country_iso2"], item["name"], item["qid"], item["phone"]))

    seed = {
        "generated_at": date.today().isoformat(),
        "source_name": "Wikidata EntityData JSON (bounded organization sample)",
        "source_url": "https://www.wikidata.org/wiki/Special:EntityData/",
        "license_name": "CC0-1.0",
        "license_url": "https://creativecommons.org/publicdomain/zero/1.0/",
        "usage_note": (
            "Discovered through allowed Wikipedia category pages. Phone values "
            "are public organization contact data, not subscriber identity."
        ),
        "countries": [{"iso2": iso2, "name": name} for iso2, name in countries],
        "entries": entries,
    }
    paths.SEED_DIR.mkdir(parents=True, exist_ok=True)
    seed_path = paths.SEED_DIR / "wikidata_entities.json"
    seed_path.write_text(json.dumps(seed, ensure_ascii=False, indent=1), encoding="utf-8")

    report = {
        "generatedAt": utc_now(),
        "countries": len(countries),
        "categoriesChecked": len(category_tasks),
        "articlesChecked": len(articles),
        "qidsFound": len(qids),
        "entriesWithPhone": len(entries),
        "categoryResults": sorted(category_report, key=lambda item: (item["countryIso2"], item["category"])),
        "articleResults": sorted(article_report, key=lambda item: (item["countryIso2"], item["article"])),
        "entityErrors": entity_errors,
    }
    output = paths.CATALOG_DIR / "wikidata-discovery.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    with connection:
        connection.execute(
            "UPDATE source_registry SET last_checked_at=?, last_successful_access_at=?, updated_at=? WHERE source_id='wikidata-entitydata'",
            (utc_now(), utc_now(), utc_now()),
        )
        db.audit(
            connection,
            "discovery.wikidata_sample",
            details={
                "countries": len(countries),
                "qids": len(qids),
                "entries": len(entries),
            },
        )
    return {
        "seed": str(seed_path),
        "report": str(output),
        "countries": len(countries),
        "articles": len(articles),
        "qids": len(qids),
        "entries": len(entries),
    }
