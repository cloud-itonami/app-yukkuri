"""yukkuri `uploadYoutube` graph — publish rendered mp4 to YouTube via Data API v3.

NSID: com.etzhayyim.apps.yukkuri.uploadYoutube

Actor: did:web:yukkuri.etzhayyim.com:actor:publisher

OAuth2 refresh-token flow (shared with etzhayyim-project-youtube). Secrets read
from env (loaded from vault by k8s pod):

  YOUTUBE_CLIENT_ID
  YOUTUBE_CLIENT_SECRET
  YOUTUBE_REFRESH_TOKEN
  YOUTUBE_DEFAULT_PRIVACY     unlisted | public | private (default: unlisted)
  YOUTUBE_DEFAULT_CATEGORY_ID 22 (People & Blogs)  default
  YOUTUBE_CHANNEL_DISPLAY     for description signature

Sequence:
  1. SELECT vertex_yukkuri_video — title / language / topic / render_blob_key
     + per-lang subtitles (vertex_yukkuri_asset kind='subtitle').
  2. Refresh OAuth2 access_token.
  3. GET rendered mp4 from PDS blob layer.
  4. POST videos.insert (resumable upload, single chunk).
  5. For each subtitle lang: POST captions.insert.
  6. UPDATE vertex_yukkuri_video.status = 'published', store youtube_video_id
     in vertex_yukkuri_generation as stage='youtube_upload'.
  7. Audit emit.

Failure modes are non-fatal for the pipeline (video still rendered locally).
"""

from __future__ import annotations

import json
import logging
import os
import time
from datetime import datetime, timezone
from typing import Any, TypedDict

import httpx
from langgraph.graph import END, START, StateGraph
from langgraph.types import RetryPolicy

from lg_yukkuri.audit import emit_audit_bg

_log = logging.getLogger(__name__)

_PDS_BLOB_FETCH = os.environ.get(
    "PDS_BLOB_FETCH_URL", "https://atproto.etzhayyim.com/xrpc/com.atproto.sync.getBlob",
)

_CLIENT_ID = os.environ.get("YOUTUBE_CLIENT_ID", "")
_CLIENT_SECRET = os.environ.get("YOUTUBE_CLIENT_SECRET", "")
_REFRESH_TOKEN = os.environ.get("YOUTUBE_REFRESH_TOKEN", "")
_DEFAULT_PRIVACY = os.environ.get("YOUTUBE_DEFAULT_PRIVACY", "unlisted")
_DEFAULT_CATEGORY = os.environ.get("YOUTUBE_DEFAULT_CATEGORY_ID", "22")
_CHANNEL_DISPLAY = os.environ.get(
    "YOUTUBE_CHANNEL_DISPLAY", "ゆきり & まりり / yukkuri.etzhayyim.com",
)
_UPLOAD_TIMEOUT = float(os.environ.get("YOUTUBE_UPLOAD_TIMEOUT_SEC", "900"))

_PUBLISHER_DID = os.environ.get(
    "YUKKURI_PUBLISHER_DID", "did:web:yukkuri.etzhayyim.com:actor:publisher",
)

_TOKEN_URL = "https://oauth2.googleapis.com/token"
_VIDEOS_INSERT_URL = (
    "https://www.googleapis.com/upload/youtube/v3/videos"
    "?uploadType=resumable&part=snippet,status"
)
_CAPTIONS_INSERT_URL = (
    "https://www.googleapis.com/upload/youtube/v3/captions"
    "?uploadType=multipart&part=snippet"
)


class _State(TypedDict, total=False):
    video_id: str
    privacy: str | None
    category_id: str | None
    description_extra: str | None
    title_override: str | None
    # internal
    video_row: dict | None
    subtitles: list[dict] | None
    access_token: str | None
    youtube_video_id: str | None
    captions_uploaded: list[str] | None
    error: str | None


async def _fetch_video_and_subtitles(video_id: str) -> tuple[dict, list[dict]]:
    import asyncio
    from kotodama.kotoba_datomic import get_kotoba_client
    client = get_kotoba_client()
    raw_video = await asyncio.to_thread(client.select_where, "vertex_yukkuri_video", "video_id", video_id, limit=1)
    if not raw_video:
        return {}, []
    r = raw_video[0]
    video_row = {
        "title": r.get("title") or f"yukkuri:{video_id}",
        "topic": r.get("topic") or "",
        "language": r.get("language") or "ja",
        "render_blob_key": r.get("render_blob_key") or "",
        "render_url": r.get("render_url") or "",
        "target_sec": int(r.get("target_sec") or 0),
        "resolution": r.get("resolution") or "1080p",
        "fps": int(r.get("fps") or 30),
    }

    raw_assets = await asyncio.to_thread(client.select_where, "vertex_yukkuri_asset", "video_id", video_id, limit=50)
    subs = []
    for r in raw_assets:
        if r.get("kind") != "subtitle":
            continue
        try:
            m = json.loads(r.get("meta_json") or "{}")
            lang = m.get("lang")
            if lang and r.get("blob_key"):
                subs.append({"lang": lang, "blob_key": r.get("blob_key")})
        except Exception:
            pass
    return video_row, subs


async def _node_fetch(state: _State) -> dict[str, Any]:
    video_id = state.get("video_id") or ""
    if not video_id:
        return {"error": "video_id required"}
    if not (_CLIENT_ID and _CLIENT_SECRET and _REFRESH_TOKEN):
        return {"error": "YOUTUBE_* credentials missing"}
    try:
        video_row, subtitles = await _fetch_video_and_subtitles(video_id)
    except Exception as exc:  # noqa: BLE001
        return {"error": f"fetch: {exc!s}"[:200]}
    if not video_row or not video_row.get("render_blob_key"):
        return {"error": "video missing render_blob_key (renderVideo not yet completed)"}
    return {"video_row": video_row, "subtitles": subtitles}


async def _node_oauth_refresh(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            r = await client.post(
                _TOKEN_URL,
                data={
                    "client_id": _CLIENT_ID,
                    "client_secret": _CLIENT_SECRET,
                    "refresh_token": _REFRESH_TOKEN,
                    "grant_type": "refresh_token",
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
    except Exception as exc:  # noqa: BLE001
        return {"error": f"oauth: {exc!s}"[:200]}
    if r.status_code >= 400:
        return {"error": f"oauth {r.status_code}: {r.text[:200]}"}
    tok = r.json().get("access_token")
    if not tok:
        return {"error": "oauth: no access_token"}
    return {"access_token": tok}


async def _fetch_mp4(blob_key: str) -> bytes | None:
    try:
        async with httpx.AsyncClient(timeout=120) as client:
            r = await client.get(_PDS_BLOB_FETCH, params={"cid": blob_key})
        if r.status_code >= 400:
            return None
        return r.content
    except Exception as exc:  # noqa: BLE001
        _log.warning("mp4 blob fetch failed: %s", exc)
        return None


async def _node_upload_video(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    video_row = state.get("video_row") or {}
    token = state.get("access_token") or ""
    mp4_bytes = await _fetch_mp4(video_row.get("render_blob_key", ""))
    if not mp4_bytes:
        return {"error": "fetch rendered mp4 failed"}

    title = (state.get("title_override") or video_row.get("title") or "yukkuri").strip()[:100]
    description = (
        f"{video_row.get('topic') or ''}\n\n"
        f"— {_CHANNEL_DISPLAY}\n"
        f"{state.get('description_extra') or ''}"
    ).strip()[:5000]
    snippet = {
        "title": title,
        "description": description,
        "tags": ["yukkuri", "AI", "commentary", "etzhayyim"],
        "categoryId": state.get("category_id") or _DEFAULT_CATEGORY,
        "defaultLanguage": video_row.get("language", "ja"),
        "defaultAudioLanguage": video_row.get("language", "ja"),
    }
    status_block = {
        "privacyStatus": (state.get("privacy") or _DEFAULT_PRIVACY),
        "selfDeclaredMadeForKids": False,
        "embeddable": True,
    }

    metadata = {"snippet": snippet, "status": status_block}

    # Step 1: initiate resumable upload session
    try:
        async with httpx.AsyncClient(timeout=_UPLOAD_TIMEOUT) as client:
            r1 = await client.post(
                _VIDEOS_INSERT_URL,
                content=json.dumps(metadata).encode("utf-8"),
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json; charset=UTF-8",
                    "X-Upload-Content-Type": "video/mp4",
                    "X-Upload-Content-Length": str(len(mp4_bytes)),
                },
            )
    except Exception as exc:  # noqa: BLE001
        return {"error": f"upload session: {exc!s}"[:200]}
    if r1.status_code not in (200, 201):
        return {"error": f"upload init {r1.status_code}: {r1.text[:300]}"}
    upload_url = r1.headers.get("location") or r1.headers.get("Location")
    if not upload_url:
        return {"error": "upload init: no Location header"}

    # Step 2: PUT video bytes (single chunk)
    try:
        async with httpx.AsyncClient(timeout=_UPLOAD_TIMEOUT) as client:
            r2 = await client.put(
                upload_url,
                content=mp4_bytes,
                headers={
                    "Content-Type": "video/mp4",
                    "Content-Length": str(len(mp4_bytes)),
                },
            )
    except Exception as exc:  # noqa: BLE001
        return {"error": f"upload put: {exc!s}"[:200]}
    if r2.status_code not in (200, 201):
        return {"error": f"upload put {r2.status_code}: {r2.text[:300]}"}
    yt_video_id = (r2.json() or {}).get("id")
    if not yt_video_id:
        return {"error": "upload put: no id"}
    return {"youtube_video_id": yt_video_id}


async def _node_upload_captions(state: _State) -> dict[str, Any]:
    if state.get("error") or not state.get("youtube_video_id"):
        return {}
    token = state.get("access_token") or ""
    yt_id = state["youtube_video_id"]
    subs = state.get("subtitles") or []
    uploaded: list[str] = []
    for sub in subs:
        lang = sub.get("lang")
        if not lang:
            continue
        srt_bytes = await _fetch_mp4(sub.get("blob_key", ""))
        if not srt_bytes:
            continue
        # YouTube captions.insert is a multipart upload: snippet JSON + binary
        boundary = f"yukkuri{int(time.time() * 1000)}"
        snippet = {
            "snippet": {
                "videoId": yt_id,
                "language": lang,
                "name": f"{lang} (auto)",
                "isDraft": False,
            }
        }
        body = (
            f"--{boundary}\r\n"
            f"Content-Type: application/json; charset=UTF-8\r\n\r\n"
            f"{json.dumps(snippet, ensure_ascii=False)}\r\n"
            f"--{boundary}\r\n"
            f"Content-Type: application/x-subrip\r\n\r\n"
        ).encode("utf-8") + srt_bytes + f"\r\n--{boundary}--\r\n".encode("utf-8")
        try:
            async with httpx.AsyncClient(timeout=60) as client:
                rc = await client.post(
                    _CAPTIONS_INSERT_URL,
                    content=body,
                    headers={
                        "Authorization": f"Bearer {token}",
                        "Content-Type": f"multipart/related; boundary={boundary}",
                    },
                )
        except Exception as exc:  # noqa: BLE001
            _log.warning("captions %s failed: %s", lang, exc)
            continue
        if rc.status_code in (200, 201):
            uploaded.append(lang)
        else:
            _log.warning("captions %s %s: %s", lang, rc.status_code, rc.text[:200])
    return {"captions_uploaded": uploaded}


async def _node_persist(state: _State) -> dict[str, Any]:
    if state.get("error") or not state.get("youtube_video_id"):
        return {}
    video_id = state.get("video_id") or ""
    yt_id = state["youtube_video_id"]
    created_at = datetime.now(tz=timezone.utc).isoformat()
    captions = state.get("captions_uploaded") or []
    try:
        import asyncio
        from kotodama.kotoba_datomic import get_kotoba_client
        client = get_kotoba_client()
        
        # advance video status
        video_rows = await asyncio.to_thread(client.select_where, "vertex_yukkuri_video", "video_id", video_id)
        if video_rows:
            video_row = video_rows[0]
            video_row["status"] = "published"
            await asyncio.to_thread(client.insert_row, "vertex_yukkuri_video", video_row)

        params_json = json.dumps({
            "youtubeVideoId": yt_id,
            "captionsUploaded": captions,
            "privacy": state.get("privacy") or _DEFAULT_PRIVACY,
        })
        vertex_id = f"gen-yt-{video_id}-{int(time.time())}"
        await asyncio.to_thread(client.insert_row, "vertex_yukkuri_generation", {
            "vertex_id": vertex_id,
            "target_uri": f"yt://{yt_id}",
            "stage": "youtube_upload",
            "actor_did": _PUBLISHER_DID,
            "model_id": "youtube_data_api_v3",
            "params": params_json,
            "status": "published",
            "created_at": created_at
        })
    except Exception as exc:  # noqa: BLE001
        _log.exception("yt persist failed")
        return {"error": f"persist: {exc!s}"[:300]}
    return {}


async def _node_audit(state: _State) -> dict[str, Any]:
    emit_audit_bg(
        actor=_PUBLISHER_DID,
        activity="yukkuri.uploadYoutube",
        object_id=f"yt:{state.get('video_id', '')}:{int(time.time())}",
        object_type="yukkuri.youtube",
        attributes={
            "videoId": state.get("video_id"),
            "youtubeVideoId": state.get("youtube_video_id"),
            "captionsUploaded": state.get("captions_uploaded") or [],
            "ok": not bool(state.get("error")),
            "error": state.get("error"),
        },
    )
    return {}


def _build() -> StateGraph:
    g: StateGraph = StateGraph(_State)
    g.add_node("fetch", _node_fetch, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("oauth", _node_oauth_refresh, retry_policy=RetryPolicy(max_attempts=3, backoff_factor=2.0))
    g.add_node("upload_video", _node_upload_video, retry_policy=RetryPolicy(max_attempts=2, backoff_factor=5.0))
    g.add_node("upload_captions", _node_upload_captions, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("persist", _node_persist, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("audit", _node_audit)
    g.add_edge(START, "fetch")
    g.add_edge("fetch", "oauth")
    g.add_edge("oauth", "upload_video")
    g.add_edge("upload_video", "upload_captions")
    g.add_edge("upload_captions", "persist")
    g.add_edge("persist", "audit")
    g.add_edge("audit", END)
    return g


GRAPH = _build().compile(name="upload_youtube")
