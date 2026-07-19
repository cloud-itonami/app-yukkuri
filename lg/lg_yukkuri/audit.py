"""Fire-and-forget BPMN `generic.audit.emit` shim (mirrors lg-animeka pattern)."""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Any

import httpx

_log = logging.getLogger(__name__)

_DISPATCHER_URL = os.environ.get(
    "BPMN_DISPATCHER_INTERNAL_URL",
    "http://bpmn-dispatcher.mitama-udf.svc.cluster.local:8080",
).rstrip("/")
_INTERNAL_SECRET = os.environ.get("BPMN_DISPATCHER_INTERNAL_SECRET", "").strip()
_AUDIT_TIMEOUT_SEC = float(os.environ.get("LG_AUDIT_TIMEOUT_SEC", "3.0"))
_AUDIT_DISABLED = os.environ.get("LG_AUDIT_DISABLED", "0") == "1"


async def emit_audit(
    *,
    actor: str,
    activity: str,
    object_id: str,
    object_type: str,
    attributes: dict[str, Any] | None = None,
) -> None:
    if _AUDIT_DISABLED:
        return
    payload = {
        "actor": actor,
        "activity": activity,
        "objectId": object_id,
        "objectType": object_type,
        "attributes": attributes or {},
    }
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if _INTERNAL_SECRET:
        headers["x-internal-trust"] = _INTERNAL_SECRET
    url = f"{_DISPATCHER_URL}/xrpc/com.etzhayyim.generic.audit.emit"
    try:
        async with httpx.AsyncClient(timeout=_AUDIT_TIMEOUT_SEC) as client:
            await client.post(url, json=payload, headers=headers)
    except Exception as exc:  # noqa: BLE001
        _log.warning("audit.emit failed (non-fatal): %s | activity=%s id=%s", exc, activity, object_id)


def emit_audit_bg(
    *,
    actor: str,
    activity: str,
    object_id: str,
    object_type: str,
    attributes: dict[str, Any] | None = None,
) -> asyncio.Task:
    return asyncio.create_task(
        emit_audit(
            actor=actor,
            activity=activity,
            object_id=object_id,
            object_type=object_type,
            attributes=attributes,
        )
    )
