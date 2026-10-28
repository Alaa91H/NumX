"""Command-line entry point for the NumX global phone-data platform."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any, Iterable

from . import api, catalog, coverage, db, discovery, docsgen, linkcheck, paths, reports, summary
from .connectors.base import ImportContext, run_import
from .connectors.carrier import CarrierConnector
from .connectors.emergency import EmergencyConnector
from .connectors.itu import ItuConnector
from .connectors.libphonenumber import LibphoneNumberConnector
from .connectors.regulators import RegulatorConnector
from .connectors.shortnumbers import ShortNumberConnector
from .connectors.wikidata_entitydata import WikidataDirectoryConnector

CONNECTORS = {
    "libphonenumber": (LibphoneNumberConnector, "libphonenumber-metadata", "libphonenumber-metadata-9.0.42"),
    "short": (ShortNumberConnector, "libphonenumber-short", "libphonenumber-short-9.0.42"),
    "emergency": (EmergencyConnector, "numx-emergency-reference", "numx-emergency-reference-1"),
    "carrier": (CarrierConnector, "libphonenumber-carrier", None),
    "itu": (ItuConnector, "itu-nnp-index", None),
    "regulators": (RegulatorConnector, "wikipedia-regulators", None),
    "wikidata": (WikidataDirectoryConnector, "wikidata-entitydata", None),
}


def _connection() -> Any:
    paths.ensure_dirs()
    return db.initialize(paths.DB_PATH)


def _lock() -> dict[str, Any]:
    return catalog.load_sources_lock()


def _source_url(source_id: str) -> str:
    with _connection() as connection:
        row = connection.execute(
            "SELECT source_url FROM source_registry WHERE source_id=?", (source_id,)
        ).fetchone()
    if row:
        return row[0]
    return f"https://github.com/Alaa91H/NumX#{source_id}"


def _run_connector(
    name: str,
    connection: Any,
    *,
    dry_run: bool = False,
    limit: int | None = None,
    countries: Iterable[str] | None = None,
) -> dict[str, Any]:
    if name not in CONNECTORS:
        raise SystemExit(f"unknown connector: {name}")
    connector_class, source_id, release_id = CONNECTORS[name]
    connector = connector_class()
    context = ImportContext(
        connection=connection,
        source_id=source_id,
        source_url=_source_url(source_id),
        source_release_id=release_id,
        dry_run=dry_run,
        limit=limit,
        countries=list(countries) if countries else None,
    )
    return run_import(connector, context, job_label=name)


def command_catalog(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        result = catalog.seed_database(connection)
        coverage.write_coverage(connection)
        files = catalog.write_catalog_files(connection)
        summary.write_summary(connection)
        print(json.dumps({"seeded": result, "files": files}, ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_import(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        report = _run_connector(
            args.connector,
            connection,
            dry_run=args.dry_run,
            limit=args.limit,
            countries=args.country,
        )
        print(json.dumps(report, ensure_ascii=False, indent=1))
        return 0 if report["status"] in ("completed", "dry_run") else 1
    finally:
        connection.close()


def command_import_all(args: argparse.Namespace) -> int:
    connection = _connection()
    reports = []
    failed = False
    try:
        for name in ("itu", "regulators", "libphonenumber", "short", "emergency", "carrier", "wikidata"):
            report = _run_connector(
                name,
                connection,
                dry_run=args.dry_run,
                limit=args.limit,
                countries=args.country,
            )
            reports.append(report)
            if report["status"] not in ("completed", "dry_run"):
                failed = True
        coverage.write_coverage(connection)
        summary.write_summary(connection)
        catalog.write_catalog_files(connection)
        print(json.dumps(reports, ensure_ascii=False, indent=1))
        return 1 if failed else 0
    finally:
        connection.close()


def command_linkcheck(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        result = linkcheck.check_sources(
            connection,
            limit=args.limit,
            source_ids=args.source or None,
            workers=args.workers,
        )
        print(json.dumps(result, ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_discover_itu(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        result = discovery.discover_itu_documents(
            connection, limit=args.limit, workers=args.workers
        )
        print(json.dumps(result, ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_discover_wikidata(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        result = discovery.discover_wikidata(
            connection,
            max_articles_per_category=args.max_articles,
            workers=args.workers,
        )
        print(json.dumps(result, ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_coverage(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        result = coverage.write_coverage(connection)
        catalog.write_catalog_files(connection)
        summary.write_summary(connection)
        print(json.dumps(result, ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_summary(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        print(json.dumps(summary.write_summary(connection), ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_reports(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        print(json.dumps(reports.write_reports(connection), ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_docs(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        print(json.dumps(docsgen.write_docs(connection), ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_all(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        catalog.seed_database(connection)
        catalog.write_catalog_files(connection)
        for name in ("itu", "regulators", "libphonenumber", "short", "emergency", "carrier", "wikidata"):
            _run_connector(name, connection, limit=args.limit)
        coverage.write_coverage(connection)
        summary.write_summary(connection)
        catalog.write_catalog_files(connection)
        reports.write_reports(connection)
        docsgen.write_docs(connection)
        print(json.dumps({"status": "ok", "database": str(paths.DB_PATH)}, indent=1))
    finally:
        connection.close()
    return 0


def command_status(args: argparse.Namespace) -> int:
    connection = _connection()
    try:
        payload = summary.build_summary(connection, include_sources=False)
        print(json.dumps(payload["counts"], ensure_ascii=False, indent=1))
        print(json.dumps(payload["coverage"], ensure_ascii=False, indent=1))
    finally:
        connection.close()
    return 0


def command_check(args: argparse.Namespace) -> int:
    connection = _connection()
    problems: list[str] = []
    try:
        entities = db.table_count(connection, "countries_and_regions")
        sources = db.table_count(connection, "source_registry")
        coverage_rows = db.table_count(connection, "coverage_matrix")
        expected = entities * len(coverage.CATEGORY_ORDER)
        if entities == 0:
            problems.append("no entities")
        if sources == 0:
            problems.append("no sources")
        if coverage_rows != expected:
            problems.append(f"coverage rows {coverage_rows} != expected {expected}")
        if db.table_count(connection, "record_provenance") == 0:
            problems.append("no provenance rows")
        forbidden = connection.execute(
            """
            SELECT source_id, import_status, legal_review_status
            FROM source_registry
            WHERE legal_review_status='prohibited'
              AND import_status NOT IN ('blocked_by_robots','blocked_license','blocked_authentication','temporarily_unavailable')
            """
        ).fetchall()
        for row in forbidden:
            problems.append(f"prohibited source not blocked: {row['source_id']}")
        missing_license = connection.execute(
            "SELECT source_id FROM source_registry WHERE license_name IS NULL OR license_name=''"
        ).fetchall()
        for row in missing_license:
            problems.append(f"source without license: {row['source_id']}")
        for table, entity_type in (
            ("number_ranges", "number_range"),
            ("carrier_observations", "carrier_observation"),
            ("regulator_entries", "regulator_entry"),
            ("directory_entries", "directory_entry"),
        ):
            total = db.table_count(connection, table)
            if total:
                with_prov = connection.execute(
                    f"""
                    SELECT COUNT(*) FROM {table} t WHERE EXISTS(
                      SELECT 1 FROM record_provenance p
                      WHERE p.entity_type=?
                        AND (p.entity_id=CAST(t.rowid AS TEXT)
                             OR (p.source_id=t.source_id AND p.source_record_id=t.source_record_id))
                    )
                    """,
                    (entity_type,),
                ).fetchone()[0]
                if with_prov == 0:
                    problems.append(f"{table} has records but no matching provenance")
        payload = {
            "status": "ok" if not problems else "failed",
            "problems": problems,
            "counts": {
                "entities": entities,
                "sources": sources,
                "coverageRows": coverage_rows,
                "expectedCoverageRows": expected,
                "provenance": db.table_count(connection, "record_provenance"),
            },
        }
        print(json.dumps(payload, ensure_ascii=False, indent=1))
        return 0 if not problems else 1
    finally:
        connection.close()


def command_serve(args: argparse.Namespace) -> int:
    api.serve(args.host, args.port, args.db)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python3 -m tools.platform.cli",
        description="NumX global phone-data platform tooling",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("catalog", help="seed the database and write catalog files")
    p.set_defaults(func=command_catalog)

    p = sub.add_parser("import", help="run one connector")
    p.add_argument("connector", choices=sorted(CONNECTORS))
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--limit", type=int)
    p.add_argument("--country", action="append", help="limit to an ISO2 code (repeatable)")
    p.set_defaults(func=command_import)

    p = sub.add_parser("import-all", help="run every permitted connector")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--limit", type=int)
    p.add_argument("--country", action="append")
    p.set_defaults(func=command_import_all)

    p = sub.add_parser("linkcheck", help="check catalogued source URLs")
    p.add_argument("--limit", type=int)
    p.add_argument("--source", action="append")
    p.add_argument("--workers", type=int, default=4)
    p.set_defaults(func=command_linkcheck)

    p = sub.add_parser("discover-itu", help="discover ITU document links")
    p.add_argument("--limit", type=int)
    p.add_argument("--workers", type=int, default=4)
    p.set_defaults(func=command_discover_itu)

    p = sub.add_parser("discover-wikidata", help="discover the bounded Wikidata sample")
    p.add_argument("--max-articles", type=int, default=4)
    p.add_argument("--workers", type=int, default=4)
    p.set_defaults(func=command_discover_wikidata)

    for name, func, help_text in (
        ("coverage", command_coverage, "recompute coverage"),
        ("summary", command_summary, "write summaries"),
        ("reports", command_reports, "write per-country reports"),
        ("docs", command_docs, "write root documentation"),
        ("status", command_status, "print current counts"),
        ("check", command_check, "run integrity checks"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.set_defaults(func=func)

    p = sub.add_parser("all", help="catalog, import, coverage, summary, reports, docs")
    p.add_argument("--limit", type=int)
    p.set_defaults(func=command_all)

    p = sub.add_parser("serve", help="serve the read-only API")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--db", default=str(paths.DB_PATH))
    p.set_defaults(func=command_serve)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
