# lg-yukkuri — LangGraph Server actor for yukkuri.etzhayyim.com

AI ゆっくり動画生成パイプライン。OSS LangGraph FastAPI pattern (mirrors lg-animeka).

## Layout

```
lg/
├── langgraph.json                       # 10 graphs manifest + RW checkpointer
├── pyproject.toml
├── Dockerfile                           # OSS, no licensed base
├── lg_yukkuri/
│   ├── __init__.py
│   ├── state.py                         # VideoState / SceneState / LineState / AssetState / GeneratePipelineState
│   ├── audit.py                         # fire-and-forget BPMN generic.audit.emit
│   ├── checkpointer.py                  # _RwAsyncPostgresSaver (RisingWave ON CONFLICT 回避)
│   ├── cron.py                          # APScheduler in-process
│   ├── server.py                        # FastAPI: /runs /runs/stream /xrpc/{nsid} /ok /health
│   └── graphs/
│       ├── health.py                    # ✅ com.etzhayyim.apps.yukkuri.health
│       ├── list_videos.py               # ✅ com.etzhayyim.apps.yukkuri.listVideos
│       ├── get_video.py                 # ✅ com.etzhayyim.apps.yukkuri.getVideo
│       ├── compose.py                   # ✅ com.etzhayyim.apps.yukkuri.compose
│       ├── generate_script.py           # ✅ com.etzhayyim.apps.yukkuri.generateScript
│       ├── synthesize_voice.py          # ✅ com.etzhayyim.apps.yukkuri.synthesizeVoice
│       ├── generate_visual.py           # ✅ com.etzhayyim.apps.yukkuri.generateVisual
│       ├── generate_bgm.py              # ✅ com.etzhayyim.apps.yukkuri.generateBgm
│       ├── render_video.py              # ✅ com.etzhayyim.apps.yukkuri.renderVideo
│       └── review_video.py              # ✅ com.etzhayyim.apps.yukkuri.reviewVideo
└── tests/                               # ⏳ next iteration
```

## Pipeline

```
compose (status: queued)
  → generate_script (status: script)
    → [synthesize_voice + generate_visual + generate_bgm] 並列 (CF Worker onCommit が駆動)
      → render_video (status: rendered)   ← Phase 0: Mac render pool
        → review_video → published / rejected
          → [DERIVED] app.bsky.feed.post (T1 Social)
```

## Phases

| Phase | Scope | Status |
|---|---|---|
| **P1** LangGraph scaffold | 10 graphs + server + Dockerfile + Helm chart | ✅ 2026-05-12 |
| **P1** DB migration | `20260512130000_lg_yukkuri_shorthand_cols` — video_id / scene_id / scene_index etc. | ✅ 2026-05-12 |
| **P1** Helm chart | `50-infra/vultr/lg-yukkuri-pool/` (mirrors lg-animeka-pool) | ✅ 2026-05-12 |
| **P1** build + push amd64 | `docker buildx … ghcr.io/etzhayyim/lg-yukkuri:0.1.0-amd64` | ⏳ |
| **P1** Helm install | `helm upgrade --install lg-yukkuri 50-infra/vultr/lg-yukkuri-pool/` | ⏳ |
| **P1** CF tunnel route | cloudflared-bpmn-dispatcher ConfigMap に yukkuri NSID 追加 | ⏳ |
| **P2** Mac render pool | `yukkuri-renderer` service (serve_plain.py + kami-engine CLI) | ⏳ |
| **P2** kokoro-ts TTS | `murakumo:inference/audio` に kokoro provider 追加 | ⏳ |
| **P3** 独自立ち絵セット | GL-clean reimu-like / marisa-like 立ち絵 (ゆきり / まりり) | ⏳ |
| **P3** CF Browser Rendering | 短尺 (<60s) 並列 render dispatcher | ⏳ |

## NSID coverage (10 of 10)

| NSID | assistant_id | graph file | status |
|---|---|---|---|
| `com.etzhayyim.apps.yukkuri.health` | `health` | health.py | ✅ |
| `com.etzhayyim.apps.yukkuri.listVideos` | `list_videos` | list_videos.py | ✅ |
| `com.etzhayyim.apps.yukkuri.getVideo` | `get_video` | get_video.py | ✅ |
| `com.etzhayyim.apps.yukkuri.compose` | `compose` | compose.py | ✅ |
| `com.etzhayyim.apps.yukkuri.generateScript` | `generate_script` | generate_script.py | ✅ |
| `com.etzhayyim.apps.yukkuri.synthesizeVoice` | `synthesize_voice` | synthesize_voice.py | ✅ |
| `com.etzhayyim.apps.yukkuri.generateVisual` | `generate_visual` | generate_visual.py | ✅ |
| `com.etzhayyim.apps.yukkuri.generateBgm` | `generate_bgm` | generate_bgm.py | ✅ |
| `com.etzhayyim.apps.yukkuri.renderVideo` | `render_video` | render_video.py | ✅ |
| `com.etzhayyim.apps.yukkuri.reviewVideo` | `review_video` | review_video.py | ✅ |

## Conventions

- `VLLM_URL` defaults to RunPod unified pod `vyp99t9px7h4dl-4000` (ADR-2605010000)
- TTS: `MURAKUMO_TTS_URL` (kokoro provider, `/v1/audio/speech`)
- Image: `MURAKUMO_IMAGE_URL` (flux-schnell, `/v1/images/generations`)
- BGM: `ONGAKUKA_XRPC_URL` cross-project invoke
- Render: `YUKKURI_RENDER_POOL_URL` (Mac render pool Phase 0)
- Checkpointer: same RW PG :4566 schema as lg-animeka (`lg_checkpoints*` tables)
- Audit: `yukkuri.*` activities → bpmn-dispatcher fire-and-forget (ADR-0056 OCEL)

## DB Schema Gap (migration 20260512130000)

既存 0059 テーブルは `vertex_id` (AT URI) PK + `video_uri`/`idx` 参照。
LangGraph graphs は `video_id` (rkey) / `scene_index` / `line_index` 等を使用。
マイグレーションで両方のカラムを共存させる。INSERT 時は `vertex_id` (AT URI) と
`video_id` (rkey) を両方 populate する。
