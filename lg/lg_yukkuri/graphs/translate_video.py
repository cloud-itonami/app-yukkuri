"""yukkuri `translateVideo` graph — multilingual subtitles + dubbed voice.

NSID: com.etzhayyim.apps.yukkuri.translateVideo

Actor: did:web:yukkuri.etzhayyim.com:actor:translator

For each target language:
  1. LLM-translate every vertex_yukkuri_line.text in original timeline order.
  2. Emit one SRT file per (video, lang) — stored as vertex_yukkuri_asset
     kind='subtitle' meta_json includes {lang, format:'srt'}.
  3. (Optional, dub=true) Re-synthesize voice per line via kokoro/murakumo
     TTS in the target language, store per-line voice_blob_key under
     vertex_yukkuri_asset kind='voice_dub' meta_json includes
     {lang, lineIndex, sceneIndex, speaker, voicePreset}.

Default target langs: ["en", "zh", "ko", "es", "fr"]. Override via input.

Line timing in SRT: cumulative running offset from each line's TTS
voice_blob duration; if voice duration not yet stored, fall back to
160ms / char (Japanese cadence approximation). Matches the renderer's
expectation that lines flow scene-by-scene without overlap.
"""

from __future__ import annotations

import asyncio
import base64
import json
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

_log = logging.getLogger(__name__)

_VLLM_URL = os.environ.get(
    "VLLM_URL", "https://vyp99t9px7h4dl-4000.proxy.runpod.net/v1",
).rstrip("/")
_VLLM_MODEL = os.environ.get("VLLM_MODEL", "tier0-general")
_VLLM_TIMEOUT = float(os.environ.get("VLLM_TIMEOUT_SEC", "60"))

_TTS_URL = os.environ.get(
    "MURAKUMO_TTS_URL", "https://vyp99t9px7h4dl-4000.proxy.runpod.net/v1/audio/speech",
).rstrip("/")
_TTS_TIMEOUT = float(os.environ.get("TTS_TIMEOUT_SEC", "30"))

_PDS_BLOB_URL = os.environ.get(
    "PDS_BLOB_URL", "https://atproto.etzhayyim.com/xrpc/com.atproto.repo.uploadBlob",
)
_TRANSLATOR_DID = os.environ.get(
    "YUKKURI_TRANSLATOR_DID", "did:web:yukkuri.etzhayyim.com:actor:translator",
)

DEFAULT_LANGS = ["en", "zh", "ko", "es", "fr"]

# kokoro / murakumo voice preset per language. left=calm, right=energetic.
_VOICE_BY_LANG = {
    "en": {"left": "af_heart", "right": "am_puck"},
    "zh": {"left": "zf_xiaoxiao", "right": "zm_yunjian"},
    "ko": {"left": "kf_seol", "right": "km_taejin"},
    "es": {"left": "ef_clara", "right": "em_alonso"},
    "fr": {"left": "ff_marie", "right": "fm_henri"},
    "de": {"left": "df_anna", "right": "dm_lukas"},
    "ja": {"left": "jf_hibiki", "right": "jm_takeshi"},
}


class _Line(TypedDict, total=False):
    scene_index: int
    line_index: int
    speaker: str
    text: str
    emotion: str
    voice_blob_key: str | None
    duration_ms: int


class _State(TypedDict, total=False):
    video_id: str
    target_langs: list[str]
    dub: bool
    lines: list[_Line]
    translations: dict | None  # lang -> [translated_lines]
    subtitle_blobs: dict | None  # lang -> blob_key
    dub_blobs: dict | None  # lang -> [{sceneIndex,lineIndex,blob_key}]
    error: str | None


async def _fetch_lines(video_id: str) -> list[_Line]:
    import asyncio
    client = get_kotoba_client()
    raw_rows = await asyncio.to_thread(client.select_where, "vertex_yukkuri_line", "video_id", video_id, limit=500)
    raw_rows.sort(key=lambda x: (int(x.get("scene_index") or 0), int(x.get("line_index") or 0)))
    
    out: list[_Line] = []
    for r in raw_rows:
        text = str(r.get("text") or "")
        out.append({
            "scene_index": int(r.get("scene_index") or 0),
            "line_index": int(r.get("line_index") or 0),
            "speaker": r.get("speaker") or "left",
            "text": text,
            "emotion": r.get("emotion") or "normal",
            "voice_blob_key": r.get("voice_blob_key") or None,
            "duration_ms": max(800, int(160 * len(text))),
        })
    return out


_TRANSLATE_SYSTEM = """\
You are a professional video subtitle translator for a Japanese yukkuri
commentary channel. Translate each line into the target language while:

- Preserving meaning, tone, and humor.
- Keeping each translated line a single line (no internal newlines).
- Using natural conversational register suitable for spoken dialogue.
- Returning JSON only, no commentary.

Input: an ordered list of {idx, speaker, text, emotion}.
Output: {"lines": [{"idx": <int>, "text": "<translated>"}, ...]} in the same order.
"""


async def _translate_batch(lines: list[_Line], target_lang: str) -> list[str]:
    payload_lines = [
        {"idx": i, "speaker": l["speaker"], "text": l["text"], "emotion": l["emotion"]}
        for i, l in enumerate(lines)
    ]
    user_msg = (
        f"Target language: {target_lang}\n"
        f"Lines to translate (JSON):\n{json.dumps(payload_lines, ensure_ascii=False)}"
    )
    try:
        async with httpx.AsyncClient(timeout=_VLLM_TIMEOUT) as client:
            r = await client.post(
                f"{_VLLM_URL}/chat/completions",
                json={
                    "model": _VLLM_MODEL,
                    "messages": [
                        {"role": "system", "content": _TRANSLATE_SYSTEM},
                        {"role": "user", "content": user_msg},
                    ],
                    "max_tokens": 3500,
                    "temperature": 0.4,
                    "response_format": {"type": "json_object"},
                },
                headers={"Content-Type": "application/json"},
            )
    except Exception as exc:  # noqa: BLE001
        _log.warning("translate vllm %s failed: %s", target_lang, exc)
        return [l["text"] for l in lines]  # fallback: original text
    if r.status_code >= 400:
        _log.warning("translate vllm %s %s: %s", target_lang, r.status_code, r.text[:200])
        return [l["text"] for l in lines]
    raw = ((r.json().get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    try:
        parsed = json.loads(raw)
        items = parsed.get("lines") or []
    except Exception:
        try:
            start = raw.index("{"); end = raw.rindex("}") + 1
            items = json.loads(raw[start:end]).get("lines") or []
        except Exception:
            _log.warning("translate %s: cannot parse llm output", target_lang)
            return [l["text"] for l in lines]
    by_idx = {int(it.get("idx", -1)): str(it.get("text", "")) for it in items}
    return [by_idx.get(i, lines[i]["text"]) for i in range(len(lines))]


def _ms_to_srt(ms: int) -> str:
    if ms < 0:
        ms = 0
    h, rem = divmod(ms, 3600000)
    m, rem = divmod(rem, 60000)
    s, ms_ = divmod(rem, 1000)
    return f"{h:02d}:{m:02d}:{s:02d},{ms_:03d}"


def _build_srt(lines: list[_Line], translated: list[str]) -> str:
    out: list[str] = []
    t = 0
    for i, (line, text) in enumerate(zip(lines, translated), start=1):
        dur = max(800, int(line.get("duration_ms") or 1500))
        start = t
        end = t + dur
        out.append(str(i))
        out.append(f"{_ms_to_srt(start)} --> {_ms_to_srt(end)}")
        out.append(text.replace("\n", " ").strip())
        out.append("")
        t = end + 80  # 80 ms gap between lines
    return "\n".join(out)


async def _upload_blob(content: bytes, mime: str) -> str | None:
    async with httpx.AsyncClient(timeout=30) as client:
        ub = await client.post(
            _PDS_BLOB_URL, content=content, headers={"Content-Type": mime},
        )
    if ub.status_code >= 400:
        _log.warning("uploadBlob %s: %s", ub.status_code, ub.text[:200])
        return None
    return ub.json().get("blob", {}).get("ref", {}).get("$link", "") or None


async def _tts_one(
    text: str, *, voice: str, lang: str,
) -> bytes | None:
    try:
        async with httpx.AsyncClient(timeout=_TTS_TIMEOUT) as client:
            r = await client.post(
                _TTS_URL,
                json={
                    "model": "kokoro",
                    "input": text,
                    "voice": voice,
                    "language": lang,
                    "response_format": "wav",
                },
                headers={"Content-Type": "application/json"},
            )
    except Exception as exc:  # noqa: BLE001
        _log.warning("tts %s failed: %s", lang, exc)
        return None
    if r.status_code >= 400:
        _log.warning("tts %s %s: %s", lang, r.status_code, r.text[:200])
        return None
    return r.content or None


async def _node_fetch(state: _State) -> dict[str, Any]:
    video_id = state.get("video_id") or ""
    if not video_id:
        return {"error": "video_id required"}
    try:
        lines = await _fetch_lines(video_id)
    except Exception as exc:  # noqa: BLE001
        return {"error": f"fetch: {exc!s}"[:200]}
    if not lines:
        return {"error": f"no lines for video_id={video_id}"}
    return {
        "lines": lines,
        "target_langs": state.get("target_langs") or DEFAULT_LANGS,
        "dub": bool(state.get("dub", True)),
    }


async def _node_translate(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    lines = state.get("lines") or []
    langs = state.get("target_langs") or DEFAULT_LANGS
    results = await asyncio.gather(*[_translate_batch(lines, l) for l in langs])
    return {"translations": {l: r for l, r in zip(langs, results)}}


async def _node_subtitles(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    lines = state.get("lines") or []
    translations = state.get("translations") or {}
    subtitle_blobs: dict[str, str] = {}
    for lang, translated in translations.items():
        srt = _build_srt(lines, translated)
        blob_key = await _upload_blob(srt.encode("utf-8"), "application/x-subrip")
        if blob_key:
            subtitle_blobs[lang] = blob_key
    return {"subtitle_blobs": subtitle_blobs}


async def _node_dub(state: _State) -> dict[str, Any]:
    if state.get("error") or not state.get("dub"):
        return {}
    lines = state.get("lines") or []
    translations = state.get("translations") or {}
    out: dict[str, list[dict[str, Any]]] = {}
    for lang, translated in translations.items():
        voices = _VOICE_BY_LANG.get(lang, _VOICE_BY_LANG["en"])
        per_line: list[dict[str, Any]] = []
        for line, text in zip(lines, translated):
            speaker = line.get("speaker", "left")
            voice = voices.get(speaker) or voices["left"]
            audio = await _tts_one(text, voice=voice, lang=lang)
            if not audio:
                continue
            blob_key = await _upload_blob(audio, "audio/wav")
            if not blob_key:
                continue
            per_line.append({
                "sceneIndex": line["scene_index"],
                "lineIndex": line["line_index"],
                "speaker": speaker,
                "voicePreset": voice,
                "blobKey": blob_key,
            })
        out[lang] = per_line
    return {"dub_blobs": out}


async def _node_insert(state: _State) -> dict[str, Any]:
    if state.get("error"):
        return {}
    video_id = state.get("video_id") or ""
    subtitle_blobs = state.get("subtitle_blobs") or {}
    dub_blobs = state.get("dub_blobs") or {}
    created_at = datetime.now(tz=timezone.utc).isoformat()

    try:
        import asyncio
        client = get_kotoba_client()
        for lang, blob_key in subtitle_blobs.items():
            asset_id = f"asset-srt-{video_id}-{lang}-{secrets.token_hex(3)}"
            meta = f'{{"lang":"{lang}","format":"srt"}}'
            await asyncio.to_thread(client.insert_row, "vertex_yukkuri_asset", {
                "vertex_id": asset_id,
                "video_id": video_id,
                "kind": "subtitle",
                "actor_did": _TRANSLATOR_DID,
                "blob_key": blob_key,
                "meta_json": meta,
                "created_at": created_at
            })
        for lang, items in dub_blobs.items():
            for it in items:
                asset_id = (
                    f"asset-dub-{video_id}-{lang}-{it['sceneIndex']}-"
                    f"{it['lineIndex']}-{secrets.token_hex(2)}"
                )
                meta = json.dumps({
                    "lang": lang,
                    "format": "mp3",
                    "sceneIndex": it["sceneIndex"],
                    "lineIndex": it["lineIndex"],
                    "voicePreset": it["voicePreset"],
                }, separators=(",", ":"))
                await asyncio.to_thread(client.insert_row, "vertex_yukkuri_asset", {
                    "vertex_id": asset_id,
                    "video_id": video_id,
                    "kind": "voice_dub",
                    "actor_did": _TRANSLATOR_DID,
                    "blob_key": it["blobKey"],
                    "meta_json": meta,
                    "created_at": created_at
                })
    except Exception as exc:  # noqa: BLE001
        _log.exception("translate insert failed")
        return {"error": f"insert: {exc!s}"[:300]}
    return {}


async def _node_audit(state: _State) -> dict[str, Any]:
    subtitle_blobs = state.get("subtitle_blobs") or {}
    dub_blobs = state.get("dub_blobs") or {}
    emit_audit_bg(
        actor=_TRANSLATOR_DID,
        activity="yukkuri.translateVideo",
        object_id=f"translate:{state.get('video_id', '')}:{int(time.time())}",
        object_type="yukkuri.asset",
        attributes={
            "videoId": state.get("video_id"),
            "langs": list(subtitle_blobs.keys()),
            "subtitleCount": len(subtitle_blobs),
            "dubLineCount": sum(len(v) for v in dub_blobs.values()),
            "dub": bool(state.get("dub")),
        },
    )
    return {}


def _build() -> StateGraph:
    g: StateGraph = StateGraph(_State)
    g.add_node("fetch", _node_fetch, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("translate", _node_translate, retry_policy=RetryPolicy(max_attempts=2, backoff_factor=3.0))
    g.add_node("subtitles", _node_subtitles, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("dub", _node_dub, retry_policy=RetryPolicy(max_attempts=1))
    g.add_node("insert", _node_insert, retry_policy=RetryPolicy(max_attempts=2))
    g.add_node("audit", _node_audit)
    g.add_edge(START, "fetch")
    g.add_edge("fetch", "translate")
    g.add_edge("translate", "subtitles")
    g.add_edge("subtitles", "dub")
    g.add_edge("dub", "insert")
    g.add_edge("insert", "audit")
    g.add_edge("audit", END)
    return g


GRAPH = _build().compile(name="translate_video")
