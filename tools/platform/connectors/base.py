"""Connector interface and idempotent import runner."""

from __future__ import annotations

import json
import traceback
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping, Protocol, Sequence

from .. import db


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass
class ImportContext:
    connection: Any
    source_id: str
    source_url: str
    source_release_id: str | None = None
    dry_run: bool = False
    limit: int | None = None
    countries: Sequence[str] | None = None


@dataclass
class ImportStats:
    read: int = 0
    added: int = 0
    updated: int = 0
    skipped: int = 0
    rejected: int = 0
    errors: list[str] = field(default_factory=list)

    def reject(self, message: str) -> None:
        self.rejected += 1
        if len(self.errors) < 50:
            self.errors.append(message)


class Connector(Protocol):
    connector_id: str
    connector_version: str
    source_id: str
    entity_type: str
    target_table: str
    conflict_columns: tuple[str, ...]

    def records(self, context: ImportContext) -> Iterable[Mapping[str, Any]]:
        ...

    def validate(self, record: Mapping[str, Any]) -> list[str]:
        ...

    def to_row(self, record: Mapping[str, Any], context: ImportContext) -> dict[str, Any]:
        ...

    def provenance(
        self,
        record: Mapping[str, Any],
        row: Mapping[str, Any],
        context: ImportContext,
        job_id: str,
    ) -> Iterable[dict[str, Any]]:
        ...


def _conflict_where(conflict_columns: Sequence[str]) -> str:
    return " AND ".join(f"{column}=?" for column in conflict_columns)


def _exists(connection: Any, table: str, conflict_columns: Sequence[str], row: Mapping[str, Any]) -> bool:
    sql = f"SELECT 1 FROM {table} WHERE {_conflict_where(conflict_columns)} LIMIT 1"
    values = [row[column] for column in conflict_columns]
    return connection.execute(sql, values).fetchone() is not None


def run_import(
    connector: Connector,
    context: ImportContext,
    *,
    job_label: str = "",
) -> dict[str, Any]:
    """Run a connector inside a savepoint/transaction and return a report.

    A failed record is rejected without aborting the whole import.  A fatal
    error rolls the data changes back, so a partial download can never delete
    or corrupt previously imported records.
    """

    connection = context.connection
    job_id = str(uuid.uuid4())
    started = utc_now()
    connection.execute(
        """
        INSERT INTO import_jobs(job_id, connector_id, source_id, started_at, status, dry_run)
        VALUES(?,?,?,?,?,?)
        """,
        (job_id, connector.connector_id, context.source_id, started, "running", db.as_bool(context.dry_run)),
    )
    connection.commit()

    stats = ImportStats()
    fatal: str | None = None
    savepoint = f"import_{job_id.replace('-', '_')}"
    connection.execute(f"SAVEPOINT {savepoint}")
    try:
        for record in connector.records(context):
            if context.limit is not None and stats.read >= context.limit:
                break
            stats.read += 1
            errors = connector.validate(record)
            if errors:
                stats.reject(f"record {stats.read}: " + "; ".join(errors))
                continue
            row = connector.to_row(record, context)
            if row is None:
                stats.skipped += 1
                continue
            existed = _exists(connection, connector.target_table, connector.conflict_columns, row)
            db.upsert(connection, connector.target_table, row, connector.conflict_columns)
            if existed:
                stats.updated += 1
            else:
                stats.added += 1
            entity_id = row.get("range_id") or row.get("phone_id") or row.get("entry_id") or row.get("observation_id") or row.get("report_id") or row.get("plan_id")
            if entity_id is None:
                entity_id = "|".join(str(row.get(column, "")) for column in connector.conflict_columns)
            for provenance in connector.provenance(record, row, context, job_id):
                db.add_provenance(
                    connection,
                    entity_type=provenance.get("entity_type", connector.entity_type),
                    entity_id=provenance.get("entity_id", entity_id),
                    field_name=provenance.get("field_name", "record"),
                    source_id=provenance.get("source_id", context.source_id),
                    source_record_id=provenance.get("source_record_id"),
                    source_url=provenance.get("source_url", context.source_url),
                    source_release_id=context.source_release_id,
                    import_job_id=job_id,
                    connector_version=connector.connector_version,
                    original_value=provenance.get("original_value"),
                    normalized_value=provenance.get("normalized_value"),
                    transformations=provenance.get("transformations", ""),
                    verification=provenance.get("verification", "REFERENCE"),
                    license_name=provenance.get("license_name", "unknown"),
                    license_url=provenance.get("license_url"),
                    retrieved_at=provenance.get("retrieved_at", started),
                    source_updated_at=provenance.get("source_updated_at"),
                    last_verified_at=provenance.get("last_verified_at"),
                )
        if context.dry_run:
            connection.execute(f"ROLLBACK TO {savepoint}")
            connection.execute(f"RELEASE {savepoint}")
        else:
            connection.execute(f"RELEASE {savepoint}")
            connection.commit()
    except Exception as exc:
        connection.execute(f"ROLLBACK TO {savepoint}")
        connection.execute(f"RELEASE {savepoint}")
        connection.rollback()
        fatal = f"{type(exc).__name__}: {exc}"
        stats.errors.append(fatal)
        stats.errors.append(traceback.format_exc(limit=5))

    finished = utc_now()
    status = "failed" if fatal else ("dry_run" if context.dry_run else "completed")
    report = {
        "job_id": job_id,
        "connector_id": connector.connector_id,
        "source_id": context.source_id,
        "label": job_label,
        "started_at": started,
        "finished_at": finished,
        "status": status,
        "dry_run": context.dry_run,
        "read": stats.read,
        "added": stats.added,
        "updated": stats.updated,
        "skipped": stats.skipped,
        "rejected": stats.rejected,
        "errors": stats.errors,
    }
    connection.execute(
        """
        UPDATE import_jobs SET finished_at=?, status=?, records_read=?, records_added=?,
            records_updated=?, records_skipped=?, records_rejected=?, errors_json=?, report_json=?
        WHERE job_id=?
        """,
        (
            finished,
            status,
            stats.read,
            stats.added,
            stats.updated,
            stats.skipped,
            stats.rejected,
            json.dumps(stats.errors, ensure_ascii=False),
            json.dumps(report, ensure_ascii=False),
            job_id,
        ),
    )
    db.audit(
        connection,
        "import.completed" if status == "completed" else f"import.{status}",
        entity_type="import_job",
        entity_id=job_id,
        details={"connector_id": connector.connector_id, "status": status, "read": stats.read},
    )
    connection.commit()
    return report
