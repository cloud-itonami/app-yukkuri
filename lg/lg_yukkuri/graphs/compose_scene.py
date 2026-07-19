"""yukkuri `composeScene` graph — per-scene composite (BG + L立ち絵 + R立ち絵).

NSID: com.etzhayyim.apps.yukkuri.composeScene

Actor: did:web:yukkuri.etzhayyim.com:actor:editor

For each scene we already have:
  - one `background` asset (from generateVisual)
  - character_sheet assets for L and R at various emotions (from generateCharacter)

This graph reads the dominant emotion of each scene's lines (most frequent
across speakers), picks the matching character sheets, uploads the three
images to ComfyUI's input/ dir, and runs `scene_composite_workflow` to
produce the final widescreen frame per scene. Result is stored as
vertex_yukkuri_asset kind='scene' (the timeline frame the renderer consumes).
"""

from __future__ import annotations

import base64
import json
import logging
import os
import secrets
import time
from collections import Counter
from datetime import datetime, timezone
from typing import Any, TypedDict

import httpx
from langgraph.graph import END, START, StateGraph
from langgraph.types import RetryPolicy

from lg_yukkuri.audit import emit_audit_bg
from lg_yukkuri.comfy_runner import (
    DEFAULT_URL as COMFY_URL,
    run_workflow,
    upload_image_b64,
)
from lg_yukkuri.comfy_workflows import scene_composite_workflow

_log = logging.getLogger(__name__)

_COMFY_TIMEOUT = int(os.environ.get("COMFY_TIMEOUT_SEC", "180"))
_PDS_BLOB_URL = os.environ.get(
    "PDS_BLOB_URL", "https://atproto.etzhayyim.com/xrpc/com.atproto.repo.uploadBlob",
)
_PDS_BLOB_FETCH = os.environ.get(
    "PDS_BLOB_FETCH_URL", "https://atproto.etzhayyim.com/xrpc/com.atproto.sync.getBlob",
)
_EDITOR_DID = os.environ.get(
    "YUKKURI_EDITOR_DID", "did:web:yukkuri.etzhayyim.com:actor:editor",
)


class _State(TypedDict, total=False):
    video_id: str
    scenes_meta: list[dict] | None  # {scene_index, dominant_emotion}
    backgrounds: dict | None        # {scene_index: blob_key}
    sheets: dict | None             # {(side, emotion): blob_key}
    composites: list[dict] | None
    error: str | None


async def _fetch_blob(blob_key: str) -> bytes | None:
    if not blob_key:
        return None
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            r = await client.get(_PDS_BLOB_FETCH, params={"cid": blob_key})
        if r.status_code >= 400:
            return None
        return r.content
    except Exception as exc:  # noqa: BLE001
        _log.warning("blob fetch failed: %s", exc)
        return None


async def _upload_blob_bytes(image_bytes: bytes) -> str | None:
    async with httpx.AsyncClient(timeout=30.0) as client:
        ub = await client.post(
            _PDS_BLOB_URL, content=image_bytes,
            headers={"Content-Type": "image/png"},
        )
    if ub.status_code >= 400:
        return None
    return ub.json().get("blob", {}).get("ref", {}).get("$link", "") or None


async def _node_plan(state: _State) -> dict[str, Any]:
    """Read scenes + lines + assets, decide per-scene dominant emotion."""
    if not state.get("video_id"):
        return {"error": "video_id required"}
    video_id = state["video_id"]

    import asyncio
    client = get_kotoba_client()
    raw_scenes = await asyncio.to_thread(client.select_where, "vertex_yukkuri_scene", "video_id", video_id, limit=20)
    scene_idxs = sorted([int(r.get("scene_index") or 0) for r in raw_scenes])

    raw_lines = await asyncio.to_thread(client.select_where, "vertex_yukkuri_line", "video_id", video_id, limit=500)
    line_rows = [(int(r.get("scene_index") or 0), r.get("emotion") or "normal") for r in raw_lines]

    raw_assets = await asyncio.to_thread(client.select_where, "vertex_yukkuri_asset", "video_id", video_id, limit=200)
    asset_rows = [(r.get("kind"), r.get("blob_key"), r.get("meta_json")) for r in raw_assets]

    # per-scene dominant emotion
    by_scene: dict[int, list[str]] = {}
    for sidx, emo in line_rows:
        by_scene.setdefault(int(sidx), []).append((emo or "normal").lower())
    scenes_meta: list[dict] = []
    for sidx in scene_idxs:
        emos = by_scene.get(int(sidx), ["normal"])
        dominant = Counter(emos).most_common(1)[0][0]
        scenes_meta.append({"scene_index": int(sidx), "dominant_emotion": dominant})

    # backgrounds: scene_index → blob_key
    backgrounds: dict[int, str] = {}
    sheets: dict[str, str] = {}  # "left|normal" → blob_key
    for kind, blob_key, meta_raw in asset_rows:
        try:
            meta = json.loads(meta_raw or "{}")
        except Exception:
            meta = {}
        if kind == "background" and meta.get("sceneIndex") is not None:
            backgrounds[int(meta["sceneIndex"])] = blob_key
        elif kind == "character_sheet":
            side = (meta.get("side") or "").lower()
            emo = (meta.get("emotion") or "normal").lower()
            if side in ("left", "right"):
                sheets[f"{side}|{emo}"] = blob_key

    return {
        "scenes_meta": scenes_meta,
        "backgrounds": backgrounds,
        "sheets": sheets,
    }


def _pick_sheet(sheets: dict, side: str, emotion: str) -> str | None:
    """Return blob_key for (side, emotion) with fallback to (side, normal)."""
    key = sheets.get(f"{side}|{emotion}")
    if key:
        return key
    return sheets.get(f"{side}|normal")


async def _composite_one(
    *,
    video_id: str,
    scene_index: int,
    emotion: str,
    bg_blob: str,
    left_blob: str,
    right_blob: str,
) -> dict[str, Any]:
    # Fetch the 3 inputs from PDS blob layer, push them to ComfyUI input/
    bg_b = await _fetch_blob(bg_blob)
    lf_b = await _fetch_blob(left_blob)
    rt_b = await _fetch_blob(right_blob)
    if not (bg_b and lf_b and rt_b):
        return {"scene_index": scene_index, "error": "blob fetch failed"}

    up_bg = await upload_image_b64(
        base64.b64encode(bg_b).decode("ascii"),
        comfy_url=COMFY_URL, filename_hint=f"yk-bg-{video_id}-{scene_index}",
    )
    up_lf = await upload_image_b64(
        base64.b64encode(lf_b).decode("ascii"),
        comfy_url=COMFY_URL, filename_hint=f"yk-l-{video_id}-{scene_index}",
    )
    up_rt = await upload_image_b64(
        base64.b64encode(rt_b).decode("ascii"),
        comfy_url=COMFY_URL, filename_hint=f"yk-r-{video_id}-{scene_index}",
    )
    for u in (up_bg, up_lf, up_rt):
        if u.get("error"):
            return {"scene_index": scene_index, "error": u["error"][:200]}

    wf = scene_composite_workflow(
        background_filename=up_bg["filename"],
        left_filename=up_lf["filename"],
        right_filename=up_rt["filename"],
        filename_hint=f"yk-scene-{video_id}-{scene_index}-{emotion}",
    )
    res = await run_workflow(wf, comfy_url=COMFY_URL, timeout_seconds=_COMFY_TIMEOUT)
    if res.get("status") != "ok":
        return {"scene_index": scene_index, "error": (res.get("error") or "")[:200]}
    images = res.get("images") or []
    if not images:
        return {"scene_index": scene_index, "error": "no composite image"}
    try:
        out_bytes = base64.b64decode(images[0].get("imageInlineB64", ""))
    except Exception as exc:  # noqa: BLE001
        return {"scene_index": scene_index, "error": f"decode: {exc}"[:200]}
    out_blob = await _upload_blob_bytes(out_bytes)
    if not out_blob:
        return {"scene_index": scene_index, "error": "composite uploadBlob failed"}
    return {
        "scene_index": scene_index,
        "emotion": emotion,
        "blob_key": out_blob,
        "comfy_filename": images[0].get("filename", ""),
        "elapsed_ms": res.get("elapsed_ms", 0),
    }


async def _node_composite(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    video_id = state.get("video_id") or ""
    scenes_meta = state.get("scenes_meta") or []
    backgrounds = state.get("backgrounds") or {}
    sheets = state.get("sheets") or {}

    composites: list[dict[str, Any]] = []
    for sm in scenes_meta:
        sidx = int(sm["scene_index"])
        emotion = sm["dominant_emotion"]
        bg = backgrounds.get(sidx)
        lf = _pick_sheet(sheets, "left", emotion)
        rt = _pick_sheet(sheets, "right", emotion)
        if not (bg and lf and rt):
            composites.append({
                "scene_index": sidx,
                "error": (
                    f"missing assets — bg:{bool(bg)} left:{bool(lf)} right:{bool(rt)}"
                ),
            })
            continue
        composites.append(await _composite_one(
            video_id=video_id, scene_index=sidx, emotion=emotion,
            bg_blob=bg, left_blob=lf, right_blob=rt,
        ))
    return {"composites": composites}


async def _node_insert(state: _State) -> dict[str, Any]:
    if state.get("error") or not state.get("composites"):
        return {}
    video_id = state.get("video_id") or ""
    created_at = datetime.now(tz=timezone.utc).isoformat()
    ok_rows = [c for c in (state.get("composites") or []) if not c.get("error")]
    try:
        import asyncio
        client = get_kotoba_client()
        for c in ok_rows:
            asset_id = (
                f"asset-scene-{video_id}-{c['scene_index']}-"
                f"{secrets.token_hex(3)}"
            )
            meta = (
                f'{{"sceneIndex":{c["scene_index"]},'
                f'"emotion":"{c.get("emotion", "normal")}",'
                f'"source":"comfyui-composite",'
                f'"comfyFilename":"{c.get("comfy_filename", "")}",'
                f'"elapsedMs":{c.get("elapsed_ms", 0)}}}'
            )
            await asyncio.to_thread(client.insert_row, "vertex_yukkuri_asset", {
                "vertex_id": asset_id,
                "video_id": video_id,
                "kind": "scene",
                "actor_did": _EDITOR_DID,
                "blob_key": c["blob_key"],
                "meta_json": meta,
                "created_at": created_at
            })
    except Exception as exc:  # noqa: BLE001
        _log.exception("insert scene composites failed")
        return {"error": f"insert: {exc!s}"[:300]}
    return {}


async def _node_audit(state: _State) -> dict[str, Any]:
    composites = state.get("composites") or []
    ok = [c for c in composites if not c.get("error")]
    emit_audit_bg(
        actor=_EDITOR_DID,
        activity="yukkuri.composeScene",
        object_id=f"compose:{state.get('video_id', '')}:{int(time.time())}",
        object_type="yukkuri.asset",
        attributes={
            "videoId": state.get("video_id"),
            "composited": len(ok),
            "failed": len(composites) - len(ok),
            "backend": "comfyui",
        },
    )
    return {}


def _build() -> StateGraph:
    g: StateGraph = StateGraph(_State)
    g.add_node("plan", _node_plan, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("composite", _node_composite, retry_policy=RetryPolicy(max_attempts=1))
    g.add_node("insert", _node_insert, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("audit", _node_audit)
    g.add_edge(START, "plan")
    g.add_edge("plan", "composite")
    g.add_edge("composite", "insert")
    g.add_edge("insert", "audit")
    g.add_edge("audit", END)
    return g


GRAPH = _build().compile(name="compose_scene")
