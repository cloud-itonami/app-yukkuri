"""The publish ordering of the YouTube upload graph (ADR-2608124600).

The defect: the node PUT the mp4 and only then persisted `status=published`
and the `yt://<id>` row, with no already-uploaded guard. A run that died in
between left a video that nothing in the store named, and the ordinary retry
uploaded it again — a second video on the channel, publicly.

The fix rests on something the code already had and threw away: YouTube hands
back a resumable-session URL BEFORE it will accept any bytes. Persisting that
one string is what makes the residue nameable.

The load-bearing test here is `test_retry_after_crash_...`: it simulates a
crash between the upload and the record and asserts that the next run does not
create a second video.

Nothing in this file touches the network or YouTube. Every identifier is
obviously synthetic.
"""

from __future__ import annotations

import json

import httpx
import pytest

SESSION_URL = "https://upload.example.invalid/session/SYNTHETIC-SESSION-1"
SYNTHETIC_VIDEO_ID = "SYNTHETIC_VIDEO_ID_0001"
MP4 = b"SYNTHETIC-MP4-BYTES-NOT-A-REAL-VIDEO" * 4


class Wire:
    """A fake YouTube + PDS, recording every call the node makes."""

    def __init__(self, *, put_status=200, query=None, init_status=200):
        self.calls: list[str] = []
        self.put_status = put_status
        self.query = query or {"status": 200, "id": SYNTHETIC_VIDEO_ID}
        self.init_status = init_status
        self.put_seen_at: int | None = None   # len(store.writes) when bytes went out
        self.store = None

    @property
    def sessions_opened(self) -> int:
        return self.calls.count("init")

    @property
    def uploads(self) -> int:
        return self.calls.count("put")

    def handler(self, request: httpx.Request) -> httpx.Response:
        url = str(request.url)

        if "getBlob" in url:
            self.calls.append("blob")
            return httpx.Response(200, content=MP4)

        if "videos" in url and request.method == "POST":
            self.calls.append("init")
            if self.init_status != 200:
                return httpx.Response(self.init_status, text="synthetic init failure")
            return httpx.Response(200, headers={"Location": SESSION_URL})

        if request.method == "PUT":
            crange = request.headers.get("content-range", "")
            if crange.startswith("bytes */"):
                self.calls.append("query")
                q = self.query
                if q["status"] in (200, 201):
                    return httpx.Response(q["status"], json={"id": q["id"]})
                if q["status"] == 308:
                    return httpx.Response(
                        308, headers={"Range": f"bytes=0-{q.get('received', 1) - 1}"})
                return httpx.Response(q["status"], text="synthetic")
            # the real upload
            self.calls.append("put")
            if self.store is not None:
                self.put_seen_at = len(self.store.writes)
            if self.put_status not in (200, 201):
                return httpx.Response(self.put_status, text="synthetic put failure")
            return httpx.Response(200, json={"id": SYNTHETIC_VIDEO_ID})

        return httpx.Response(404, text="unexpected call in test")


@pytest.fixture(autouse=True)
def _no_audit(monkeypatch):
    monkeypatch.setenv("LG_AUDIT_DISABLED", "1")


def _wire_up(monkeypatch, uy, wire, store):
    """Point the module's httpx at the fake wire. No sockets are opened."""
    wire.store = store

    class _Shim:
        @staticmethod
        def AsyncClient(**kwargs):  # noqa: N802 — mirrors httpx's own name
            kwargs.pop("transport", None)
            return httpx.AsyncClient(transport=httpx.MockTransport(wire.handler), **kwargs)

    monkeypatch.setattr(uy, "httpx", _Shim)


def _video_row(**over):
    row = {
        "title": "synthetic title",
        "topic": "synthetic topic",
        "language": "ja",
        "render_blob_key": "SYNTHETIC-BLOB-KEY",
        "status": "rendered",
        "youtube_video_id": "",
    }
    row.update(over)
    return row


def _state(**over):
    s = {"video_id": "vid-synthetic-1", "video_row": _video_row(),
         "access_token": "synthetic-token-not-a-real-credential"}
    s.update(over)
    return s


# ── positive controls: pass before and after the fix ─────────────────────────

@pytest.mark.asyncio
async def test_control_happy_path_still_uploads_and_returns_the_id(monkeypatch, uy, store):
    wire = Wire()
    _wire_up(monkeypatch, uy, wire, store)

    out = await uy._node_upload_video(_state())

    assert out.get("youtube_video_id") == SYNTHETIC_VIDEO_ID
    assert wire.uploads == 1
    assert "error" not in out


@pytest.mark.asyncio
async def test_control_missing_mp4_is_still_an_error(monkeypatch, uy, store):
    wire = Wire()

    def handler(request):
        return httpx.Response(404, text="no such blob")

    class _Shim:
        @staticmethod
        def AsyncClient(**kwargs):  # noqa: N802
            return httpx.AsyncClient(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr(uy, "httpx", _Shim)

    out = await uy._node_upload_video(_state())

    assert "error" in out
    assert "mp4" in out["error"]


# ── the ordering itself ──────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_session_url_is_persisted_before_any_bytes(monkeypatch, uy, store):
    """The session URL exists in the store before the PUT, not after.

    Asserted by position, not by final state: the final state looks identical
    either way, which is precisely why this defect survived review.
    """
    wire = Wire()
    _wire_up(monkeypatch, uy, wire, store)

    await uy._node_upload_video(_state())

    assert wire.put_seen_at is not None, "the bytes never went out"
    assert wire.put_seen_at >= 1, "no row was written before the bytes were committed"

    before_put = store.writes[: wire.put_seen_at]
    session_writes = [r for _t, r in before_put if r.get("target_uri") == SESSION_URL]
    assert session_writes, (
        "the resumable session URL was not persisted before the upload — "
        "a crash mid-PUT would leave a video nothing can name")
    assert session_writes[0]["status"] == "uploading"


@pytest.mark.asyncio
async def test_retry_after_crash_between_upload_and_record_does_not_republish(
        monkeypatch, uy, store):
    """**The load-bearing test.** A crash between the bytes and the record.

    Run 1 uploads successfully and then dies before `_node_persist` — so the
    video row still says `rendered` and carries no id. Run 2 is the ordinary
    retry. It must not put a second video on the channel.
    """
    wire = Wire()
    _wire_up(monkeypatch, uy, wire, store)

    out1 = await uy._node_upload_video(_state())
    assert out1.get("youtube_video_id") == SYNTHETIC_VIDEO_ID
    assert wire.uploads == 1
    # ...and now the process dies. Nothing else is written.

    # Run 2: the store still shows an unpublished video (that is the crash).
    out2 = await uy._node_upload_video(_state())

    assert wire.uploads == 1, (
        f"the retry uploaded again — {wire.uploads} videos on the channel "
        f"where there should be 1")
    assert out2.get("youtube_video_id") == SYNTHETIC_VIDEO_ID, (
        "the retry must recover the id of the video that already exists")


@pytest.mark.asyncio
async def test_already_published_video_is_not_uploaded_again(monkeypatch, uy, store):
    wire = Wire()
    _wire_up(monkeypatch, uy, wire, store)

    out = await uy._node_upload_video(
        _state(video_row=_video_row(status="published",
                                    youtube_video_id=SYNTHETIC_VIDEO_ID)))

    assert wire.uploads == 0, "a published video was uploaded a second time"
    assert wire.sessions_opened == 0
    assert out.get("youtube_video_id") == SYNTHETIC_VIDEO_ID


@pytest.mark.asyncio
async def test_incomplete_session_is_resumed_not_restarted(monkeypatch, uy, store):
    """A partial upload resumes from where it stopped instead of starting over."""
    wire = Wire(put_status=500)
    _wire_up(monkeypatch, uy, wire, store)

    first = await uy._node_upload_video(_state())
    assert "error" in first
    assert wire.sessions_opened == 1

    # Second run: YouTube says it holds part of the file.
    wire.put_status = 200
    wire.query = {"status": 308, "received": 8}
    out = await uy._node_upload_video(_state())

    assert wire.sessions_opened == 1, "a new session was opened instead of resuming"
    assert out.get("youtube_video_id") == SYNTHETIC_VIDEO_ID
    assert out.get("upload_resumed") is True


@pytest.mark.asyncio
async def test_unresolvable_session_refuses_rather_than_risking_a_duplicate(
        monkeypatch, uy, store):
    """If we cannot find out what happened, we do not upload again.

    Uploading on an unknown outcome is the choice that produces two videos.
    """
    wire = Wire(put_status=500)
    _wire_up(monkeypatch, uy, wire, store)

    await uy._node_upload_video(_state())
    assert wire.sessions_opened == 1

    wire.query = {"status": 503}   # cannot determine the outcome
    out = await uy._node_upload_video(_state())

    assert wire.uploads == 1, "uploaded again despite an unknown prior outcome"
    assert "error" in out
    assert "unresolved" in out["error"]


@pytest.mark.asyncio
async def test_expired_session_may_start_a_fresh_one(monkeypatch, uy, store):
    """A session YouTube has forgotten cannot have produced a video."""
    wire = Wire(put_status=500)
    _wire_up(monkeypatch, uy, wire, store)

    await uy._node_upload_video(_state())
    assert wire.sessions_opened == 1

    wire.put_status = 200
    wire.query = {"status": 404}
    out = await uy._node_upload_video(_state())

    assert wire.sessions_opened == 2, "an expired session should be replaced"
    assert out.get("youtube_video_id") == SYNTHETIC_VIDEO_ID


@pytest.mark.asyncio
async def test_persist_stores_the_id_on_the_video_row(monkeypatch, uy, store):
    """The guard needs a durable handle, so persist has to write one."""
    store.seed("vertex_yukkuri_video",
               {"video_id": "vid-synthetic-1", "title": "synthetic", "status": "rendered"})

    await uy._node_persist(_state(youtube_video_id=SYNTHETIC_VIDEO_ID,
                                  captions_uploaded=[]))

    row = store.select_where("vertex_yukkuri_video", "video_id", "vid-synthetic-1")[0]
    assert row["status"] == "published"
    assert row["youtube_video_id"] == SYNTHETIC_VIDEO_ID

    gen = [r for _t, r in store.writes if r.get("stage") == "youtube_upload"]
    assert gen and gen[0]["target_uri"] == f"yt://{SYNTHETIC_VIDEO_ID}"
    assert json.loads(gen[0]["params"])["youtubeVideoId"] == SYNTHETIC_VIDEO_ID
