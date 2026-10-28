"""SQLite storage for the global phone-data platform.

SQLite is used because the platform must be reproducible on a workstation and
inside CI without a database server.  The schema is intentionally normalized
and keeps provenance in a separate table instead of collapsing every fact into
one row.  A PostgreSQL deployment can reuse the same logical model; see
``DATABASE_SCHEMA.md``.

The database is an analytical/reference store, not a subscriber database.  It
never contains active-line status, subscriber identity, or a "current carrier"
claim after number portability.
"""

from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence

SCHEMA_VERSION = 2

SCHEMA: tuple[str, ...] = (
    """
    CREATE TABLE IF NOT EXISTS schema_migrations (
        version INTEGER PRIMARY KEY,
        description TEXT NOT NULL,
        applied_at TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS countries_and_regions (
        entity_id TEXT PRIMARY KEY,
        iso2 TEXT UNIQUE,
        name_en TEXT NOT NULL,
        name_ar TEXT NOT NULL,
        calling_code TEXT,
        entity_kind TEXT NOT NULL DEFAULT 'country',
        parent_iso2 TEXT,
        support_tier TEXT,
        itu_nnp_url TEXT,
        itu_reference TEXT,
        iso_reference TEXT,
        regulator_name TEXT,
        regulator_url TEXT,
        source_refs_json TEXT NOT NULL DEFAULT '[]',
        notes_ar TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS source_registry (
        source_id TEXT PRIMARY KEY,
        source_name TEXT NOT NULL,
        source_owner TEXT NOT NULL DEFAULT '',
        country_iso2 TEXT,
        entity_id TEXT,
        geographic_scope TEXT NOT NULL DEFAULT 'global',
        source_category TEXT NOT NULL,
        data_categories_json TEXT NOT NULL DEFAULT '[]',
        official_source INTEGER NOT NULL DEFAULT 0,
        primary_or_secondary TEXT NOT NULL DEFAULT 'secondary',
        source_url TEXT NOT NULL,
        documentation_url TEXT,
        api_base_url TEXT,
        download_url TEXT,
        access_method TEXT NOT NULL,
        authentication_required INTEGER NOT NULL DEFAULT 0,
        free_access INTEGER NOT NULL DEFAULT 1,
        commercial_subscription_required INTEGER NOT NULL DEFAULT 0,
        license_name TEXT NOT NULL DEFAULT 'unknown',
        license_url TEXT,
        redistribution_allowed TEXT NOT NULL DEFAULT 'unknown',
        commercial_use_allowed TEXT NOT NULL DEFAULT 'unknown',
        attribution_required INTEGER NOT NULL DEFAULT 0,
        share_alike_required INTEGER NOT NULL DEFAULT 0,
        retention_restrictions TEXT,
        api_rate_limits TEXT,
        terms_of_service_url TEXT,
        privacy_policy_url TEXT,
        source_language TEXT NOT NULL DEFAULT 'en',
        expected_update_frequency TEXT NOT NULL DEFAULT 'unknown',
        last_checked_at TEXT,
        last_successful_access_at TEXT,
        last_successful_import_at TEXT,
        source_release_date TEXT,
        source_revision TEXT,
        known_limitations TEXT NOT NULL DEFAULT '',
        coverage_notes TEXT NOT NULL DEFAULT '',
        quality_status TEXT NOT NULL DEFAULT 'unverified',
        verification_status TEXT NOT NULL DEFAULT 'unverified',
        legal_review_status TEXT NOT NULL DEFAULT 'review_required',
        technical_review_status TEXT NOT NULL DEFAULT 'unverified',
        import_status TEXT NOT NULL DEFAULT 'not_imported',
        discovery_source_id TEXT,
        last_http_status INTEGER,
        last_error TEXT,
        next_review_due_at TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS source_datasets (
        dataset_id TEXT PRIMARY KEY,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id) ON DELETE CASCADE,
        dataset_name TEXT NOT NULL,
        data_category TEXT NOT NULL,
        format TEXT NOT NULL,
        schema_json TEXT NOT NULL DEFAULT '{}',
        license_name TEXT NOT NULL DEFAULT 'unknown',
        license_url TEXT,
        redistribution_allowed TEXT NOT NULL DEFAULT 'unknown',
        coverage_scope TEXT NOT NULL DEFAULT '',
        known_limitations TEXT NOT NULL DEFAULT '',
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS source_releases (
        release_id TEXT PRIMARY KEY,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id) ON DELETE CASCADE,
        dataset_id TEXT REFERENCES source_datasets(dataset_id) ON DELETE SET NULL,
        release_date TEXT,
        fetched_at TEXT NOT NULL,
        content_sha256 TEXT,
        content_bytes INTEGER,
        source_url TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'fetched',
        notes TEXT NOT NULL DEFAULT ''
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS numbering_plans (
        plan_id TEXT PRIMARY KEY,
        country_iso2 TEXT NOT NULL,
        plan_name TEXT NOT NULL,
        version TEXT,
        effective_date TEXT,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_release_id TEXT REFERENCES source_releases(release_id),
        notes TEXT NOT NULL DEFAULT ''
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS number_ranges (
        range_id INTEGER PRIMARY KEY AUTOINCREMENT,
        plan_id TEXT REFERENCES numbering_plans(plan_id) ON DELETE CASCADE,
        country_iso2 TEXT NOT NULL,
        line_type TEXT NOT NULL,
        service_type TEXT NOT NULL DEFAULT '',
        prefix TEXT,
        pattern TEXT NOT NULL,
        range_start TEXT,
        range_end TEXT,
        example_number TEXT,
        national_prefix TEXT,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_record_id TEXT NOT NULL,
        source_release_id TEXT REFERENCES source_releases(release_id),
        valid_from TEXT,
        valid_to TEXT,
        verification TEXT NOT NULL DEFAULT 'REFERENCE',
        confidence TEXT NOT NULL DEFAULT 'medium',
        notes TEXT NOT NULL DEFAULT '',
        UNIQUE(source_id, source_record_id)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS phone_numbers (
        phone_id INTEGER PRIMARY KEY AUTOINCREMENT,
        country_iso2 TEXT,
        original_number TEXT NOT NULL,
        national_format TEXT,
        e164_number TEXT,
        number_type TEXT NOT NULL DEFAULT 'UNKNOWN',
        service_type TEXT NOT NULL DEFAULT '',
        range_prefix TEXT,
        allocated_carrier TEXT,
        ported_number_carrier TEXT,
        verification TEXT NOT NULL DEFAULT 'REFERENCE',
        confidence TEXT NOT NULL DEFAULT 'medium',
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_record_id TEXT NOT NULL,
        source_release_id TEXT REFERENCES source_releases(release_id),
        observed_at TEXT,
        imported_at TEXT NOT NULL,
        license_name TEXT NOT NULL DEFAULT 'unknown',
        license_url TEXT,
        limitations TEXT NOT NULL DEFAULT '',
        UNIQUE(source_id, source_record_id, original_number)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS directory_entries (
        entry_id INTEGER PRIMARY KEY AUTOINCREMENT,
        country_iso2 TEXT,
        organization_name TEXT NOT NULL,
        phone_number TEXT NOT NULL,
        e164_number TEXT,
        address TEXT,
        website TEXT,
        category TEXT,
        latitude REAL,
        longitude REAL,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_record_id TEXT NOT NULL,
        source_release_id TEXT REFERENCES source_releases(release_id),
        source_updated_at TEXT,
        imported_at TEXT NOT NULL,
        license_name TEXT NOT NULL DEFAULT 'unknown',
        license_url TEXT,
        verification TEXT NOT NULL DEFAULT 'REFERENCE',
        confidence TEXT NOT NULL DEFAULT 'medium',
        limitations TEXT NOT NULL DEFAULT '',
        UNIQUE(source_id, source_record_id, phone_number)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS carrier_observations (
        observation_id INTEGER PRIMARY KEY AUTOINCREMENT,
        country_iso2 TEXT,
        range_prefix TEXT,
        carrier_name TEXT NOT NULL,
        carrier_type TEXT NOT NULL DEFAULT 'unknown',
        observation_type TEXT NOT NULL DEFAULT 'allocated_carrier',
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_record_id TEXT NOT NULL,
        observed_at TEXT,
        imported_at TEXT NOT NULL,
        confidence TEXT NOT NULL DEFAULT 'medium',
        limitations TEXT NOT NULL DEFAULT '',
        UNIQUE(source_id, source_record_id)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS reputation_reports (
        report_id INTEGER PRIMARY KEY AUTOINCREMENT,
        country_iso2 TEXT,
        phone_number TEXT NOT NULL,
        e164_number TEXT,
        report_type TEXT NOT NULL,
        report_count INTEGER,
        period_start TEXT,
        period_end TEXT,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_record_id TEXT NOT NULL,
        observed_at TEXT,
        imported_at TEXT NOT NULL,
        verification TEXT NOT NULL DEFAULT 'REFERENCE',
        confidence TEXT NOT NULL DEFAULT 'low',
        limitations TEXT NOT NULL DEFAULT '',
        UNIQUE(source_id, source_record_id)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS regulator_entries (
        regulator_id TEXT PRIMARY KEY,
        country_iso2 TEXT NOT NULL,
        regulator_name TEXT NOT NULL,
        official_url TEXT,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        discovery_source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        verification TEXT NOT NULL DEFAULT 'DISCOVERY',
        confidence TEXT NOT NULL DEFAULT 'low',
        checked_at TEXT,
        notes TEXT NOT NULL DEFAULT '',
        UNIQUE(country_iso2, regulator_name)
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS record_provenance (
        provenance_id INTEGER PRIMARY KEY AUTOINCREMENT,
        entity_type TEXT NOT NULL,
        entity_id TEXT NOT NULL,
        field_name TEXT NOT NULL,
        source_id TEXT NOT NULL REFERENCES source_registry(source_id),
        source_record_id TEXT,
        source_url TEXT,
        source_release_id TEXT REFERENCES source_releases(release_id),
        import_job_id TEXT,
        connector_version TEXT NOT NULL,
        schema_version INTEGER NOT NULL,
        original_value TEXT,
        normalized_value TEXT,
        transformations TEXT NOT NULL DEFAULT '',
        verification TEXT NOT NULL DEFAULT 'REFERENCE',
        license_name TEXT NOT NULL DEFAULT 'unknown',
        license_url TEXT,
        retrieved_at TEXT NOT NULL,
        source_updated_at TEXT,
        last_verified_at TEXT
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS provenance_history (
        history_id INTEGER PRIMARY KEY AUTOINCREMENT,
        provenance_id INTEGER,
        entity_type TEXT NOT NULL,
        entity_id TEXT NOT NULL,
        field_name TEXT NOT NULL,
        source_id TEXT NOT NULL,
        source_record_id TEXT,
        source_url TEXT,
        source_release_id TEXT,
        import_job_id TEXT,
        connector_version TEXT NOT NULL,
        schema_version INTEGER NOT NULL,
        original_value TEXT,
        normalized_value TEXT,
        transformations TEXT NOT NULL DEFAULT '',
        verification TEXT NOT NULL DEFAULT 'REFERENCE',
        license_name TEXT NOT NULL DEFAULT 'unknown',
        license_url TEXT,
        retrieved_at TEXT NOT NULL,
        source_updated_at TEXT,
        last_verified_at TEXT,
        replaced_at TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS import_jobs (
        job_id TEXT PRIMARY KEY,
        connector_id TEXT NOT NULL,
        source_id TEXT,
        started_at TEXT NOT NULL,
        finished_at TEXT,
        status TEXT NOT NULL,
        dry_run INTEGER NOT NULL DEFAULT 0,
        records_read INTEGER NOT NULL DEFAULT 0,
        records_added INTEGER NOT NULL DEFAULT 0,
        records_updated INTEGER NOT NULL DEFAULT 0,
        records_skipped INTEGER NOT NULL DEFAULT 0,
        records_rejected INTEGER NOT NULL DEFAULT 0,
        errors_json TEXT NOT NULL DEFAULT '[]',
        report_json TEXT NOT NULL DEFAULT '{}'
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS audit_events (
        event_id INTEGER PRIMARY KEY AUTOINCREMENT,
        occurred_at TEXT NOT NULL,
        actor TEXT NOT NULL,
        action TEXT NOT NULL,
        entity_type TEXT,
        entity_id TEXT,
        details_json TEXT NOT NULL DEFAULT '{}'
    )
    """,
    """
    CREATE TABLE IF NOT EXISTS coverage_matrix (
        country_iso2 TEXT NOT NULL,
        data_category TEXT NOT NULL,
        status TEXT NOT NULL,
        source_count INTEGER NOT NULL DEFAULT 0,
        imported_record_count INTEGER NOT NULL DEFAULT 0,
        provenance_coverage REAL,
        freshness_days INTEGER,
        reason_ar TEXT NOT NULL DEFAULT '',
        reason_en TEXT NOT NULL DEFAULT '',
        last_verified_at TEXT,
        PRIMARY KEY(country_iso2, data_category)
    )
    """,
    "CREATE INDEX IF NOT EXISTS idx_source_country ON source_registry(country_iso2)",
    "CREATE INDEX IF NOT EXISTS idx_source_category ON source_registry(source_category)",
    "CREATE INDEX IF NOT EXISTS idx_source_import_status ON source_registry(import_status)",
    "CREATE INDEX IF NOT EXISTS idx_source_entity ON source_registry(entity_id)",
    "CREATE INDEX IF NOT EXISTS idx_ranges_country_type ON number_ranges(country_iso2, line_type)",
    "CREATE INDEX IF NOT EXISTS idx_phones_country ON phone_numbers(country_iso2)",
    "CREATE INDEX IF NOT EXISTS idx_phones_e164 ON phone_numbers(e164_number)",
    "CREATE INDEX IF NOT EXISTS idx_directory_phone ON directory_entries(e164_number)",
    "CREATE INDEX IF NOT EXISTS idx_directory_country ON directory_entries(country_iso2)",
    "CREATE INDEX IF NOT EXISTS idx_carrier_prefix ON carrier_observations(country_iso2, range_prefix)",
    "CREATE INDEX IF NOT EXISTS idx_reputation_phone ON reputation_reports(e164_number)",
    "CREATE INDEX IF NOT EXISTS idx_regulator_country ON regulator_entries(country_iso2)",
    "CREATE INDEX IF NOT EXISTS idx_provenance_entity ON record_provenance(entity_type, entity_id)",
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_provenance_unique ON record_provenance(entity_type, entity_id, field_name, source_id, source_record_id)",
    "CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_events(occurred_at)",
)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def connect(path: Path | str = ":memory:") -> sqlite3.Connection:
    """Open a database with the platform pragmas and row access by name."""

    connection = sqlite3.connect(str(path), timeout=30)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA journal_mode = WAL")
    connection.execute("PRAGMA synchronous = NORMAL")
    connection.execute("PRAGMA busy_timeout = 5000")
    return connection


def _ensure_columns(connection: sqlite3.Connection, table: str, columns: Mapping[str, str]) -> None:
    existing = {row[1] for row in connection.execute(f"PRAGMA table_info({table})")}
    for name, declaration in columns.items():
        if name not in existing:
            connection.execute(f"ALTER TABLE {table} ADD COLUMN {name} {declaration}")


def migrate(connection: sqlite3.Connection) -> None:
    """Create or upgrade the schema.  Safe to call repeatedly."""

    with connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS schema_migrations (
                version INTEGER PRIMARY KEY,
                description TEXT NOT NULL,
                applied_at TEXT NOT NULL
            )
            """
        )
        for statement in SCHEMA:
            connection.execute(statement)
        # v1 databases predate these columns/tables.  The CREATE TABLE
        # statements above are idempotent, and ALTER TABLE keeps old data.
        _ensure_columns(
            connection,
            "source_registry",
            {
                "entity_id": "TEXT",
                "verification_status": "TEXT NOT NULL DEFAULT 'unverified'",
                "discovery_source_id": "TEXT",
                "last_http_status": "INTEGER",
                "last_error": "TEXT",
            },
        )
        _ensure_columns(
            connection,
            "coverage_matrix",
            {"reason_en": "TEXT NOT NULL DEFAULT ''"},
        )
        applied = {row[0] for row in connection.execute("SELECT version FROM schema_migrations")}
        if SCHEMA_VERSION not in applied:
            connection.execute(
                "INSERT INTO schema_migrations(version, description, applied_at) VALUES(?,?,?)",
                (SCHEMA_VERSION, "platform schema with regulator and provenance history", utc_now()),
            )


def initialize(path: Path | str) -> sqlite3.Connection:
    connection = connect(path)
    migrate(connection)
    return connection


def json_dumps(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def as_bool(value: bool | None) -> int:
    return 1 if value else 0


def _columns(row: Mapping[str, Any]) -> tuple[list[str], list[Any]]:
    names = list(row)
    return names, [row[name] for name in names]


def upsert(
    connection: sqlite3.Connection,
    table: str,
    row: Mapping[str, Any],
    conflict: Sequence[str],
) -> None:
    """Insert or update one row using an explicit conflict target."""

    names, values = _columns(row)
    placeholders = ",".join("?" for _ in names)
    updates = ",".join(
        f"{name}=excluded.{name}" for name in names if name not in conflict
    )
    sql = (
        f"INSERT INTO {table} ({','.join(names)}) VALUES ({placeholders}) "
        f"ON CONFLICT({','.join(conflict)}) DO UPDATE SET {updates}"
    )
    connection.execute(sql, values)


def upsert_source(connection: sqlite3.Connection, source: Mapping[str, Any]) -> None:
    row = dict(source)
    now = utc_now()
    row.setdefault("created_at", now)
    row["updated_at"] = now
    if "data_categories_json" not in row:
        row["data_categories_json"] = json_dumps(row.pop("data_categories", []))
    upsert(connection, "source_registry", row, ("source_id",))


def audit(
    connection: sqlite3.Connection,
    action: str,
    *,
    actor: str = "platform-cli",
    entity_type: str | None = None,
    entity_id: str | None = None,
    details: Mapping[str, Any] | None = None,
) -> None:
    connection.execute(
        """
        INSERT INTO audit_events(occurred_at, actor, action, entity_type, entity_id, details_json)
        VALUES(?,?,?,?,?,?)
        """,
        (utc_now(), actor, action, entity_type, entity_id, json_dumps(details or {})),
    )


PROVENANCE_COLUMNS = (
    "provenance_id",
    "entity_type",
    "entity_id",
    "field_name",
    "source_id",
    "source_record_id",
    "source_url",
    "source_release_id",
    "import_job_id",
    "connector_version",
    "schema_version",
    "original_value",
    "normalized_value",
    "transformations",
    "verification",
    "license_name",
    "license_url",
    "retrieved_at",
    "source_updated_at",
    "last_verified_at",
)


def add_provenance(
    connection: sqlite3.Connection,
    *,
    entity_type: str,
    entity_id: str | int,
    field_name: str,
    source_id: str,
    source_record_id: str | None,
    source_url: str | None,
    source_release_id: str | None,
    import_job_id: str | None,
    connector_version: str,
    original_value: str | None,
    normalized_value: str | None,
    transformations: str,
    verification: str,
    license_name: str,
    license_url: str | None,
    retrieved_at: str,
    source_updated_at: str | None = None,
    last_verified_at: str | None = None,
) -> None:
    """Keep one current provenance row per field and retain changed versions.

    Re-importing the same source must not multiply provenance rows.  When the
    substantive values change, the previous version is copied to
    ``provenance_history`` before the current row is updated.
    """

    entity_id_text = str(entity_id)
    existing = connection.execute(
        """
        SELECT * FROM record_provenance
        WHERE entity_type=? AND entity_id=? AND field_name=? AND source_id=?
          AND source_record_id IS ?
        LIMIT 1
        """,
        (entity_type, entity_id_text, field_name, source_id, source_record_id),
    ).fetchone()
    values = (
        source_url,
        source_release_id,
        import_job_id,
        connector_version,
        SCHEMA_VERSION,
        original_value,
        normalized_value,
        transformations,
        verification,
        license_name,
        license_url,
        retrieved_at,
        source_updated_at,
        last_verified_at,
    )
    if existing is None:
        connection.execute(
            """
            INSERT INTO record_provenance(
                entity_type, entity_id, field_name, source_id, source_record_id,
                source_url, source_release_id, import_job_id, connector_version,
                schema_version, original_value, normalized_value, transformations,
                verification, license_name, license_url, retrieved_at,
                source_updated_at, last_verified_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                entity_type,
                entity_id_text,
                field_name,
                source_id,
                source_record_id,
                *values,
            ),
        )
        return

    substantive = (
        "source_url",
        "source_release_id",
        "original_value",
        "normalized_value",
        "transformations",
        "verification",
        "license_name",
        "license_url",
        "source_updated_at",
        "last_verified_at",
    )
    changed = any(existing[name] != dict(zip(
        (
            "source_url",
            "source_release_id",
            "import_job_id",
            "connector_version",
            "schema_version",
            "original_value",
            "normalized_value",
            "transformations",
            "verification",
            "license_name",
            "license_url",
            "retrieved_at",
            "source_updated_at",
            "last_verified_at",
        ),
        values,
    ))[name] for name in substantive)
    if changed:
        connection.execute(
            """
            INSERT INTO provenance_history(
                provenance_id, entity_type, entity_id, field_name, source_id,
                source_record_id, source_url, source_release_id, import_job_id,
                connector_version, schema_version, original_value,
                normalized_value, transformations, verification, license_name,
                license_url, retrieved_at, source_updated_at, last_verified_at,
                replaced_at
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """,
            (
                existing["provenance_id"],
                existing["entity_type"],
                existing["entity_id"],
                existing["field_name"],
                existing["source_id"],
                existing["source_record_id"],
                existing["source_url"],
                existing["source_release_id"],
                existing["import_job_id"],
                existing["connector_version"],
                existing["schema_version"],
                existing["original_value"],
                existing["normalized_value"],
                existing["transformations"],
                existing["verification"],
                existing["license_name"],
                existing["license_url"],
                existing["retrieved_at"],
                existing["source_updated_at"],
                existing["last_verified_at"],
                utc_now(),
            ),
        )
    connection.execute(
        """
        UPDATE record_provenance SET
            source_url=?, source_release_id=?, import_job_id=?,
            connector_version=?, schema_version=?, original_value=?,
            normalized_value=?, transformations=?, verification=?,
            license_name=?, license_url=?, retrieved_at=?,
            source_updated_at=?, last_verified_at=?
        WHERE provenance_id=?
        """,
        (*values, existing["provenance_id"]),
    )


@contextmanager
def transaction(connection: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    try:
        connection.execute("BEGIN")
        yield connection
    except Exception:
        connection.rollback()
        raise
    else:
        connection.commit()


def table_count(connection: sqlite3.Connection, table: str) -> int:
    return int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])


def distinct_count(connection: sqlite3.Connection, table: str, column: str) -> int:
    return int(
        connection.execute(
            f"SELECT COUNT(DISTINCT {column}) FROM {table} WHERE {column} IS NOT NULL"
        ).fetchone()[0]
    )


def rows_to_dicts(rows: Iterable[sqlite3.Row]) -> list[dict[str, Any]]:
    return [dict(row) for row in rows]
