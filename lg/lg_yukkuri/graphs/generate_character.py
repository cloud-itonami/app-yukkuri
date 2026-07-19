"""yukkuri `generateCharacter` graph — L/R 立ち絵 via ComfyUI.

NSID: com.etzhayyim.apps.yukkuri.generateCharacter

Actor: did:web:yukkuri.etzhayyim.com:actor:character

Generates the per-video L/R 立ち絵 sheet (ゆきり / まりり, or whatever
description the user passes for the video). Default emotion = "normal";
emotion-specific sheets are produced per-scene by `compose_scene` when the
line.emotion changes.

Stored as vertex_yukkuri_asset with kind='character_sheet' and meta_json
encoding {side, emotion, name}.
"""

from __future__ import annotations

import base64
import logging
import os
import secrets
import time
from datetime import datetime, timezone
from typing import Any, TypedDict

import httpx
from langgraph.graph import END, START, StateGraph
from langgraph.types import RetryPolicy

from lg_yukkuri.audit import emit_audit_bg
from lg_yukkuri.comfy_runner import run_workflow, DEFAULT_URL as COMFY_URL
from lg_yukkuri.comfy_workflows import (
    character_workflow,
    LEFT_CHARACTER_DEFAULT,
    RIGHT_CHARACTER_DEFAULT,
)

_log = logging.getLogger(__name__)

_COMFY_TIMEOUT = int(os.environ.get("COMFY_TIMEOUT_SEC", "300"))
_PDS_BLOB_URL = os.environ.get(
    "PDS_BLOB_URL", "https://atproto.etzhayyim.com/xrpc/com.atproto.repo.uploadBlob",
)
_CHARACTER_DID = os.environ.get(
    "YUKKURI_CHARACTER_DID", "did:web:yukkuri.etzhayyim.com:actor:character",
)

# Default emotion set per video — minimal coverage. Per-scene overrides happen
# in compose_scene when a specific line.emotion is needed.
_DEFAULT_EMOTIONS = ["normal", "happy", "surprised"]


class _State(TypedDict, total=False):
    video_id: str
    left_name: str | None
    right_name: str | None
    left_description: str | None
    right_description: str | None
    emotions: list[str] | None
    sheets: list[dict] | None
    error: str | None


async def _upload_blob(image_bytes: bytes) -> str | None:
    async with httpx.AsyncClient(timeout=30) as client:
        ub = await client.post(
            _PDS_BLOB_URL, content=image_bytes,
            headers={"Content-Type": "image/png"},
        )
    if ub.status_code >= 400:
        return None
    return ub.json().get("blob", {}).get("ref", {}).get("$link", "") or None


async def _one_sheet(
    *,
    name: str,
    side: str,
    description: str | None,
    emotion: str,
    video_id: str,
) -> dict[str, Any]:
    wf = character_workflow(
        name=name,
        side=side,
        description=description,
        emotion=emotion,
    )
    res = await run_workflow(wf, comfy_url=COMFY_URL, timeout_seconds=_COMFY_TIMEOUT)
    if res.get("status") != "ok":
        return {"side": side, "emotion": emotion, "error": (res.get("error") or "")[:200]}
    images = res.get("images") or []
    if not images:
        return {"side": side, "emotion": emotion, "error": "no images"}
    try:
        img_bytes = base64.b64decode(images[0].get("imageInlineB64", ""))
    except Exception as exc:  # noqa: BLE001
        return {"side": side, "emotion": emotion, "error": f"decode: {exc}"[:200]}
    blob_key = await _upload_blob(img_bytes)
    if not blob_key:
        return {"side": side, "emotion": emotion, "error": "uploadBlob failed"}
    return {
        "side": side, "emotion": emotion, "name": name,
        "blob_key": blob_key,
        "comfy_filename": images[0].get("filename", ""),
        "elapsed_ms": res.get("elapsed_ms", 0),
    }


async def _node_plan(state: _State) -> dict[str, Any]:
    if not state.get("video_id"):
        return {"error": "video_id required"}
    return {}


async def _node_generate(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    video_id = state.get("video_id") or ""
    left_name = state.get("left_name") or LEFT_CHARACTER_DEFAULT
    right_name = state.get("right_name") or RIGHT_CHARACTER_DEFAULT
    emotions = state.get("emotions") or _DEFAULT_EMOTIONS

    sheets: list[dict[str, Any]] = []
    for emotion in emotions:
        sheets.append(await _one_sheet(
            name=left_name, side="left",
            description=state.get("left_description"),
            emotion=emotion, video_id=video_id,
        ))
        sheets.append(await _one_sheet(
            name=right_name, side="right",
            description=state.get("right_description"),
            emotion=emotion, video_id=video_id,
        ))
    ok = [s for s in sheets if not s.get("error")]
    return {"sheets": ok}


async def _node_insert(state: _State) -> dict[str, Any]:
    if state.get("error") or not state.get("sheets"):
        return {}
    video_id = state.get("video_id") or ""
    created_at = datetime.now(tz=timezone.utc).isoformat()
    try:
        import asyncio
        from kotodama.kotoba_datomic import get_kotoba_client
        client = get_kotoba_client()
        for s in state["sheets"]:
            asset_id = (
                f"asset-char-{video_id}-{s['side']}-{s['emotion']}-"
                f"{secrets.token_hex(3)}"
            )
            meta = (
                f'{{"side":"{s["side"]}","emotion":"{s["emotion"]}",'
                f'"name":"{s.get("name", "")}",'
                f'"comfyFilename":"{s.get("comfy_filename", "")}",'
                f'"elapsedMs":{s.get("elapsed_ms", 0)}}}'
            )
            await asyncio.to_thread(client.insert_row, "vertex_yukkuri_asset", {
                "vertex_id": asset_id,
                "video_id": video_id,
                "kind": "character_sheet",
                "actor_did": _CHARACTER_DID,
                "blob_key": s["blob_key"],
                "meta_json": meta,
                "created_at": created_at
            })
    except Exception as exc:  # noqa: BLE001
        _log.exception("insert character sheets failed")
        return {"error": f"insert: {exc!s}"[:300]}
    return {}


async def _node_audit(state: _State) -> dict[str, Any]:
    emit_audit_bg(
        actor=_CHARACTER_DID,
        activity="yukkuri.generateCharacter",
        object_id=f"character:{state.get('video_id', '')}:{int(time.time())}",
        object_type="yukkuri.asset",
        attributes={
            "videoId": state.get("video_id"),
            "sheetsOk": len(state.get("sheets") or []),
            "backend": "comfyui",
        },
    )
    return {}


def _build() -> StateGraph:
    g: StateGraph = StateGraph(_State)
    g.add_node("plan", _node_plan)
    g.add_node("generate", _node_generate, retry_policy=RetryPolicy(max_attempts=1))
    g.add_node("insert", _node_insert, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("audit", _node_audit)
    g.add_edge(START, "plan")
    g.add_edge("plan", "generate")
    g.add_edge("generate", "insert")
    g.add_edge("insert", "audit")
    g.add_edge("audit", END)
    return g


GRAPH = _build().compile(name="generate_character")
