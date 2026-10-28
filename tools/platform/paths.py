"""Filesystem locations used by the platform tooling."""

from __future__ import annotations

from pathlib import Path

PACKAGE_DIR = Path(__file__).resolve().parent
REPO_ROOT = PACKAGE_DIR.parents[1]

DATA_DIR = REPO_ROOT / "data"
CATALOG_DIR = DATA_DIR / "source-catalog"
IMPORT_DIR = DATA_DIR / "imports"
RESEARCH_DIR = REPO_ROOT / "docs" / "country-source-research"
DOCS_DIR = REPO_ROOT / "docs"
DB_PATH = DATA_DIR / "platform.db"
SUMMARY_PATH = DATA_DIR / "platform-summary.json"

SEED_DIR = PACKAGE_DIR / "seeds"
ITU_SEED = SEED_DIR / "itu_nnp.json"
REGULATOR_SEED = SEED_DIR / "regulators.json"
GLOBAL_SOURCES_SEED = SEED_DIR / "global_sources.json"

APP_ASSETS = REPO_ROOT / "numx-android" / "app" / "src" / "main" / "assets" / "metadata"
APP_COUNTRIES = APP_ASSETS / "countries.json"
APP_EMERGENCY = APP_ASSETS / "emergency.json"
APP_PLATFORM_SUMMARY = APP_ASSETS / "platform_summary.json"

PHONE_METADATA = REPO_ROOT / "tools" / "metadata" / "sources" / "PhoneNumberMetadata.xml"
SHORT_METADATA = REPO_ROOT / "tools" / "metadata" / "sources" / "ShortNumberMetadata.xml"
SOURCES_LOCK = REPO_ROOT / "tools" / "metadata" / "sources.lock.json"


def ensure_dirs() -> None:
    for path in (DATA_DIR, CATALOG_DIR, IMPORT_DIR, RESEARCH_DIR, DOCS_DIR):
        path.mkdir(parents=True, exist_ok=True)
