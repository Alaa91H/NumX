#!/usr/bin/env python3
"""Minimal platform sanity checks that must pass before any release."""

import sqlite3
import sys

def check():
    db_path = "data/platform.db"
    errors = []
    try:
        conn = sqlite3.connect(db_path)
        conn.execute("SELECT 1 FROM schema_migrations WHERE version>=2")
        entities = conn.execute("SELECT COUNT(*) FROM countries_and_regions").fetchone()[0]
        sources = conn.execute("SELECT COUNT(*) FROM source_registry").fetchone()[0]
        if entities < 200:
            errors.append(f"too few entities: {entities}")
        if sources < 10:
            errors.append(f"too few sources: {sources}")
        provenance = conn.execute("SELECT COUNT(*) FROM record_provenance").fetchone()[0]
        audit = conn.execute("SELECT COUNT(*) FROM audit_events").fetchone()[0]
        coverage = conn.execute("SELECT COUNT(*) FROM coverage_matrix").fetchone()[0]
        if coverage < (entities * 5):
            errors.append(f"coverage looks low: {coverage} rows for {entities} entities")
        blockeds = conn.execute(
            "SELECT source_id, import_status FROM source_registry WHERE import_status LIKE '%blocked%'"
        ).fetchall()
        for sid, status in blockeds:
            # Blocked sources must stay blocked; this is the audit, not a failure.
            pass
        conn.close()
    except Exception as exc:
        errors.append(str(exc))
    files = [
        "data/source-catalog/countries.json",
        "data/source-catalog/country-source-catalog.csv",
        "data/source-catalog/country-coverage-matrix.csv",
        "data/platform.db",
    ]
    import os
    for f in files:
        if not os.path.exists(f):
            errors.append(f"missing file: {f}")
    docs = [
        "GLOBAL_DATA_SOURCES.md",
        "DATA_COVERAGE.md",
        "DATA_LICENSES.md",
        "DATABASE_SCHEMA.md",
        "PROVENANCE_AND_AUDIT.md",
    ]
    for d in docs:
        if not os.path.exists(d):
            errors.append(f"missing doc: {d}")
    if errors:
        print("FAILURES:")
        for e in errors:
            print(" -", e)
        sys.exit(1)
    else:
        print("OK: platform database and artifacts present.")
        sys.exit(0)

if __name__ == "__main__":
    check()
