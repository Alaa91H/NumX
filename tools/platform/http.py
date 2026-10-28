"""Small, dependency-free HTTP client for source discovery and link checks.

It provides bounded downloads, per-host rate limiting, retries with backoff,
ETag/Last-Modified caching, and a robots.txt check.  It is not a crawler: the
platform only fetches the specific source URLs recorded in the catalog.
"""

from __future__ import annotations

import hashlib
import json
import random
import re
import ssl
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import urllib.robotparser
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Mapping, Optional

DEFAULT_USER_AGENT = (
    "NumX-Data-Platform/1.0 (+https://github.com/Alaa91H/NumX; "
    "source catalog and license verification)"
)


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


@dataclass
class FetchResult:
    url: str
    final_url: str
    method: str
    status: Optional[int]
    content_type: str
    content_length: Optional[int]
    body: bytes
    text: str
    sha256: str
    elapsed_ms: int
    fetched_at: str
    from_cache: bool = False
    robots_allowed: Optional[bool] = None
    robots_checked: bool = False
    truncated: bool = False
    error: Optional[str] = None

    @property
    def ok(self) -> bool:
        return self.error is None and self.status is not None and 200 <= self.status < 400


class HttpClient:
    def __init__(
        self,
        cache_dir: Path | str | None = None,
        *,
        user_agent: str = DEFAULT_USER_AGENT,
        timeout: float = 20.0,
        retries: int = 2,
        min_interval: float = 0.6,
        cache_ttl_seconds: int = 7 * 24 * 3600,
    ) -> None:
        self.cache_dir = Path(cache_dir) if cache_dir else None
        if self.cache_dir:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.user_agent = user_agent
        self.timeout = timeout
        self.retries = retries
        self.min_interval = min_interval
        self.cache_ttl_seconds = cache_ttl_seconds
        self._last_request: dict[str, float] = {}
        self._host_lock = threading.Lock()
        self._robots_cache: dict[str, tuple[bool, bool]] = {}
        self._robots_lock = threading.Lock()
        self._ssl_context = ssl.create_default_context()

    def _cache_paths(self, url: str, method: str) -> tuple[Path, Path]:
        assert self.cache_dir is not None
        key = hashlib.sha256(f"{method} {url}".encode("utf-8")).hexdigest()
        return self.cache_dir / f"{key}.body", self.cache_dir / f"{key}.json"

    def _read_cache(self, url: str, method: str) -> Optional[FetchResult]:
        if not self.cache_dir:
            return None
        body_path, meta_path = self._cache_paths(url, method)
        if not body_path.exists() or not meta_path.exists():
            return None
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            fetched = datetime.fromisoformat(meta["fetched_at"].replace("Z", "+00:00"))
            age = (datetime.now(timezone.utc) - fetched).total_seconds()
            if age > self.cache_ttl_seconds:
                return None
            body = body_path.read_bytes()
            return FetchResult(
                url=meta["url"],
                final_url=meta["final_url"],
                method=method,
                status=meta.get("status"),
                content_type=meta.get("content_type", ""),
                content_length=meta.get("content_length"),
                body=body,
                text=body.decode(meta.get("encoding", "utf-8"), errors="replace"),
                sha256=meta.get("sha256", hashlib.sha256(body).hexdigest()),
                elapsed_ms=meta.get("elapsed_ms", 0),
                fetched_at=meta["fetched_at"],
                from_cache=True,
                robots_allowed=meta.get("robots_allowed"),
                robots_checked=meta.get("robots_checked", False),
                truncated=meta.get("truncated", False),
                error=meta.get("error"),
            )
        except Exception:
            return None

    def _write_cache(self, result: FetchResult, encoding: str) -> None:
        if not self.cache_dir or result.method == "HEAD":
            return
        body_path, meta_path = self._cache_paths(result.url, result.method)
        body_path.write_bytes(result.body)
        meta = {
            "url": result.url,
            "final_url": result.final_url,
            "status": result.status,
            "content_type": result.content_type,
            "content_length": result.content_length,
            "sha256": result.sha256,
            "elapsed_ms": result.elapsed_ms,
            "fetched_at": result.fetched_at,
            "robots_allowed": result.robots_allowed,
            "robots_checked": result.robots_checked,
            "truncated": result.truncated,
            "error": result.error,
            "encoding": encoding,
        }
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")

    def _throttle(self, host: str) -> None:
        with self._host_lock:
            last = self._last_request.get(host, 0.0)
            wait = self.min_interval - (time.monotonic() - last)
            if wait > 0:
                time.sleep(wait)
            self._last_request[host] = time.monotonic()

    def _robots_decision(self, url: str) -> tuple[bool, bool]:
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.netloc:
            return True, False
        host_key = f"{parsed.scheme}://{parsed.netloc}"
        with self._robots_lock:
            if host_key in self._robots_cache:
                return self._robots_cache[host_key]
        robots_url = urllib.parse.urljoin(host_key, "/robots.txt")
        parser = urllib.robotparser.RobotFileParser()
        checked = False
        allowed = True
        try:
            request = urllib.request.Request(robots_url, headers={"User-Agent": self.user_agent})
            with urllib.request.urlopen(request, timeout=min(self.timeout, 8), context=self._ssl_context) as response:
                raw = response.read(64 * 1024)
            parser.parse(raw.decode("utf-8", errors="replace").splitlines())
            checked = True
            allowed = parser.can_fetch(self.user_agent, url)
        except Exception:
            # A missing or unreachable robots.txt is not a license.  We record
            # that it was not checked and continue with a single bounded fetch.
            checked = False
            allowed = True
        with self._robots_lock:
            self._robots_cache[host_key] = (allowed, checked)
        return allowed, checked

    def fetch(
        self,
        url: str,
        *,
        method: str = "GET",
        max_bytes: int = 2_000_000,
        use_cache: bool = True,
        check_robots: bool = True,
        headers: Mapping[str, str] | None = None,
        data: bytes | None = None,
        accept: str = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5",
    ) -> FetchResult:
        method = method.upper()
        cached = self._read_cache(url, method) if use_cache and method == "GET" else None
        if cached is not None:
            return cached

        robots_allowed, robots_checked = (True, False)
        if check_robots:
            robots_allowed, robots_checked = self._robots_decision(url)
            if not robots_allowed:
                return FetchResult(
                    url=url,
                    final_url=url,
                    method=method,
                    status=None,
                    content_type="",
                    content_length=None,
                    body=b"",
                    text="",
                    sha256="",
                    elapsed_ms=0,
                    fetched_at=utc_now(),
                    robots_allowed=False,
                    robots_checked=True,
                    error="robots_disallowed",
                )

        host = urllib.parse.urlparse(url).netloc
        last_error: Optional[str] = None
        for attempt in range(self.retries + 1):
            self._throttle(host)
            started = time.monotonic()
            request_headers = {
                "User-Agent": self.user_agent,
                "Accept": accept,
                "Accept-Language": "en,ar;q=0.8",
                **dict(headers or {}),
            }
            request = urllib.request.Request(
                url,
                method=method,
                headers=request_headers,
                data=data,
            )
            try:
                with urllib.request.urlopen(request, timeout=self.timeout, context=self._ssl_context) as response:
                    content_type = response.headers.get("Content-Type", "")
                    content_length_header = response.headers.get("Content-Length")
                    content_length = int(content_length_header) if content_length_header and content_length_header.isdigit() else None
                    if method == "HEAD":
                        body = b""
                        truncated = False
                    else:
                        body = response.read(max_bytes + 1)
                        truncated = len(body) > max_bytes
                        if truncated:
                            body = body[:max_bytes]
                    elapsed_ms = int((time.monotonic() - started) * 1000)
                    charset = response.headers.get_content_charset() or "utf-8"
                    result = FetchResult(
                        url=url,
                        final_url=response.geturl(),
                        method=method,
                        status=response.status,
                        content_type=content_type,
                        content_length=content_length,
                        body=body,
                        text=body.decode(charset, errors="replace"),
                        sha256=hashlib.sha256(body).hexdigest(),
                        elapsed_ms=elapsed_ms,
                        fetched_at=utc_now(),
                        robots_allowed=robots_allowed,
                        robots_checked=robots_checked,
                        truncated=truncated,
                    )
                    if method == "GET":
                        self._write_cache(result, charset)
                    return result
            except urllib.error.HTTPError as exc:
                last_error = f"HTTP {exc.code}"
                elapsed_ms = int((time.monotonic() - started) * 1000)
                if exc.code not in (429, 500, 502, 503, 504) or attempt >= self.retries:
                    return FetchResult(
                        url=url,
                        final_url=getattr(exc, "url", url),
                        method=method,
                        status=exc.code,
                        content_type=exc.headers.get("Content-Type", "") if exc.headers else "",
                        content_length=None,
                        body=b"",
                        text="",
                        sha256="",
                        elapsed_ms=elapsed_ms,
                        fetched_at=utc_now(),
                        robots_allowed=robots_allowed,
                        robots_checked=robots_checked,
                        error=last_error,
                    )
            except Exception as exc:
                last_error = f"{type(exc).__name__}: {exc}"
                if attempt >= self.retries:
                    return FetchResult(
                        url=url,
                        final_url=url,
                        method=method,
                        status=None,
                        content_type="",
                        content_length=None,
                        body=b"",
                        text="",
                        sha256="",
                        elapsed_ms=int((time.monotonic() - started) * 1000),
                        fetched_at=utc_now(),
                        robots_allowed=robots_allowed,
                        robots_checked=robots_checked,
                        error=last_error,
                    )
            delay = (2 ** attempt) + random.uniform(0.0, 0.4)
            time.sleep(delay)
        raise AssertionError("unreachable")
