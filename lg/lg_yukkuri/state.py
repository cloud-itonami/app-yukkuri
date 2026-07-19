"""Typed state schemas for yukkuri graphs."""

from __future__ import annotations

from typing import Literal, TypedDict

VideoStatus = Literal[
    "queued", "script", "assembled", "editready", "rendered", "published", "rejected"
]

Speaker = Literal["left", "right"]


class VideoState(TypedDict, total=False):
    """One yukkuri video project."""
    video_id: str           # rkey
    video_uri: str          # at://...
    topic: str
    outline: str | None     # optional user-provided scene outline
    status: VideoStatus
    owner_did: str
    project_id: str         # = convo thread id
    error: str | None


class SceneState(TypedDict, total=False):
    """One scene within a video."""
    video_id: str
    scene_index: int
    location: str
    action: str
    lines: list[dict]       # [{speaker, text, emotion}]
    background_blob_key: str | None
    sfx_blob_key: str | None


class LineState(TypedDict, total=False):
    """One spoken line."""
    video_id: str
    scene_index: int
    line_index: int
    speaker: Speaker
    text: str
    emotion: str            # normal / happy / surprised / sad / angry
    voice_blob_key: str | None


class AssetState(TypedDict, total=False):
    """Generated asset (image / sfx / bgm / character-sequence)."""
    video_id: str
    kind: str               # image / sfx / bgm / character
    actor_did: str
    blob_key: str
    meta: dict


class GeneratePipelineState(TypedDict, total=False):
    """Full pipeline state passed between generation graphs."""
    video_id: str
    topic: str
    outline: str | None
    owner_did: str
    # script output
    scenes: list[dict]      # [{location, action, lines:[{speaker,text,emotion}]}]
    scene_count: int
    # voice output
    voice_assets: list[dict]    # [{line_id, speaker, blob_key}]
    # visual output
    visual_assets: list[dict]   # [{scene_index, kind, blob_key}]
    # bgm output
    bgm_blob_key: str | None
    # render output
    timeline_json: str | None
    render_blob_key: str | None
    render_url: str | None
    # review output
    review_passed: bool | None
    review_reason: str | None
    # status tracking
    status: VideoStatus | None
    error: str | None
    latency_ms: int
