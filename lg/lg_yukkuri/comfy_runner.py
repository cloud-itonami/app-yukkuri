"""ComfyUI submit + poll for yukkuri visual generation.

Mirrors the mangaka pattern (`60-apps/etzhayyim-project-mangaka/lg/lg_mangaka/
comfy_runner.py`). Centralised so background / character / scene composite
workflows share submission, polling, and image fetch.

Default host = Windows AMD ROCm 7.2 ComfyUI at 192.168.1.70:8188
(ADR 2605222000 — animeka v3 USD + ComfyUI pipeline). Override with
COMFY_POD_URL env.
"""

from __future__ import annotations

import asyncio
import base64
import io
import os
import time
import uuid
from typing import Any

import httpx

DEFAULT_URL = (
    os.environ.get("COMFY_POD_URL")
    or os.environ.get("COMFYUI_POD_URL")
    or os.environ.get("COMFYUI_URL")
    or "http://192.168.1.70:8188"
).rstrip("/")


async def upload_image_b64(
    image_b64: str,
    *,
    comfy_url: str = DEFAULT_URL,
    filename_hint: str = "ref",
    image_mime: str = "image/png",
) -> dict[str, Any]:
    """POST a base64 image to /upload/image. Returns {filename, type, subfolder}
    for plugging into a LoadImage node's image widget."""
    try:
        data = base64.b64decode(image_b64)
    except Exception as exc:  # noqa: BLE001
        return {"error": f"invalid base64: {exc}"}

    ext = "png" if "png" in image_mime else ("jpg" if "jpeg" in image_mime else "bin")
    filename = f"{filename_hint}-{uuid.uuid4().hex[:8]}.{ext}"
    files = {"image": (filename, io.BytesIO(data), image_mime)}
    form = {"type": "input"}
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            r = await client.post(
                f"{comfy_url.rstrip('/')}/upload/image",
                files=files, data=form,
                headers={"user-agent": "lg-yukkuri-comfy/0.1"},
            )
    except Exception as exc:  # noqa: BLE001
        return {"error": f"upload failed: {exc}"}
    if r.status_code != 200:
        return {"error": f"/upload/image HTTP {r.status_code}: {r.text[:200]}"}
    j = r.json() or {}
    return {
        "filename": j.get("name", filename),
        "subfolder": j.get("subfolder", ""),
        "type": j.get("type", "input"),
    }


async def submit_workflow(
    workflow: dict,
    *,
    comfy_url: str = DEFAULT_URL,
    client_id: str | None = None,
) -> dict[str, Any]:
    """POST /prompt — returns {prompt_id, number, started_at_ms}."""
    if not isinstance(workflow, dict) or not workflow:
        return {"status": "error", "error": "workflow (object) required"}
    base = comfy_url.rstrip("/")
    body: dict[str, Any] = {"prompt": workflow}
    if client_id:
        body["client_id"] = client_id

    started_ms = int(time.time() * 1000)
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            r = await client.post(
                f"{base}/prompt", json=body,
                headers={"user-agent": "lg-yukkuri-comfy/0.1"},
            )
    except Exception as exc:  # noqa: BLE001
        return {"status": "error", "error": f"POST /prompt failed: {exc}",
                "started_at_ms": started_ms}

    if r.status_code != 200:
        return {"status": "error",
                "error": f"/prompt HTTP {r.status_code}: {r.text[:300]}",
                "started_at_ms": started_ms}

    j = r.json() or {}
    pid = j.get("prompt_id")
    if not pid:
        return {"status": "error",
                "error": f"/prompt missing prompt_id: {j}",
                "started_at_ms": started_ms}

    return {
        "prompt_id": pid,
        "number": int(j.get("number") or 0),
        "started_at_ms": started_ms,
    }


async def poll_outputs(
    prompt_id: str,
    *,
    comfy_url: str = DEFAULT_URL,
    started_at_ms: int = 0,
    timeout_seconds: int = 300,
    poll_interval_ms: int = 1500,
) -> dict[str, Any]:
    """Loop /history/{id} + fetch /view per image artifact.
    Returns {status, images, raw_history, elapsed_ms, error}.

    Each image: {node, filename, type, subfolder, imageInlineB64, imageMime, byteLen}.
    """
    base = comfy_url.rstrip("/")
    if not prompt_id:
        return {"status": "error", "error": "no prompt_id", "elapsed_ms": 0}

    timeout_s = max(10, min(900, int(timeout_seconds)))
    interval_ms = max(250, min(10000, int(poll_interval_ms)))
    deadline = time.monotonic() + timeout_s
    headers = {"user-agent": "lg-yukkuri-comfy/0.1"}
    started_at_ms = started_at_ms or int(time.time() * 1000)
    images: list[dict[str, Any]] = []
    last_history: dict[str, Any] = {}

    async with httpx.AsyncClient(timeout=30.0) as client:
        while time.monotonic() < deadline:
            try:
                hr = await client.get(f"{base}/history/{prompt_id}", headers=headers)
            except Exception:  # noqa: BLE001
                await asyncio.sleep(interval_ms / 1000.0)
                continue
            if hr.status_code != 200:
                await asyncio.sleep(interval_ms / 1000.0)
                continue

            entry = (hr.json() or {}).get(prompt_id)
            if not entry:
                await asyncio.sleep(interval_ms / 1000.0)
                continue
            last_history = entry

            status = (entry.get("status") or {})
            for m in (status.get("messages") or []):
                if isinstance(m, list) and len(m) >= 2 and m[0] in (
                    "execution_error", "execution_interrupted",
                ):
                    return {
                        "status": "error",
                        "error": f"{m[0]}: {str(m[1])[:400]}",
                        "raw_history": entry,
                        "images": images,
                        "elapsed_ms": int(time.time() * 1000) - started_at_ms,
                    }

            outputs = entry.get("outputs") or {}
            if not outputs:
                await asyncio.sleep(interval_ms / 1000.0)
                continue

            for node_id, node_out in outputs.items():
                for img in (node_out.get("images") or []):
                    params = {
                        "filename": img.get("filename", ""),
                        "subfolder": img.get("subfolder", ""),
                        "type": img.get("type", "output"),
                    }
                    try:
                        vr = await client.get(
                            f"{base}/view", headers=headers, params=params,
                        )
                    except Exception as exc:  # noqa: BLE001
                        return {
                            "status": "error",
                            "error": f"/view fetch failed: {exc}",
                            "raw_history": entry,
                            "images": images,
                            "elapsed_ms": int(time.time() * 1000) - started_at_ms,
                        }
                    if vr.status_code != 200:
                        continue
                    body = vr.content or b""
                    images.append({
                        "node": str(node_id),
                        "filename": params["filename"],
                        "type": params["type"],
                        "subfolder": params["subfolder"],
                        "imageInlineB64": base64.b64encode(body).decode("ascii"),
                        "imageMime": vr.headers.get("content-type", "image/png"),
                        "byteLen": len(body),
                    })
            return {
                "status": "ok",
                "images": images,
                "raw_history": entry,
                "elapsed_ms": int(time.time() * 1000) - started_at_ms,
            }

    return {
        "status": "timeout",
        "error": f"poll deadline {timeout_s}s exceeded",
        "raw_history": last_history,
        "images": images,
        "elapsed_ms": int(time.time() * 1000) - started_at_ms,
    }


async def run_workflow(
    workflow: dict,
    *,
    comfy_url: str = DEFAULT_URL,
    timeout_seconds: int = 300,
) -> dict[str, Any]:
    """submit + poll convenience wrapper. Returns {status, images, error}."""
    sub = await submit_workflow(workflow, comfy_url=comfy_url)
    if sub.get("status") == "error" or not sub.get("prompt_id"):
        return {"status": "error", "error": sub.get("error", "submit failed"),
                "images": []}
    return await poll_outputs(
        sub["prompt_id"],
        comfy_url=comfy_url,
        started_at_ms=int(sub.get("started_at_ms") or 0),
        timeout_seconds=timeout_seconds,
    )
