# lg-yukkuri — Clojure twin (ADR-2606280030)

Clojure-native port of the Python LangGraph server under `../lg/`
(`lg_yukkuri/`). The Python FastAPI app is the **deployed runtime** (via
`lg/langgraph.json` + Dockerfile + Helm) and this clj twin is **additive** —
it COEXISTS and runs alongside until a human cuts over. No `.py` was removed or
modified.

## What was ported (faithful)

All **10 graphs registered in `lg/langgraph.json`** → `langgraph-clj` StateGraphs,
same topology / node names / edge wiring:

| graph | NSID | topology |
|-------|------|----------|
| `health` | `…yukkuri.health` | check_rw → summarize → audit |
| `list_videos` | `…yukkuri.listVideos` | query → audit |
| `get_video` | `…yukkuri.getVideo` | fetch_video → fetch_scenes → fetch_lines → fetch_assets → audit |
| `compose` | `…yukkuri.compose` | validate → insert → audit |
| `generate_script` | `…yukkuri.generateScript` | fetch_video → llm_script → insert → audit |
| `synthesize_voice` | `…yukkuri.synthesizeVoice` | fetch_lines → synthesize → update_lines → audit |
| `generate_visual` | `…yukkuri.generateVisual` | fetch_scenes → generate → insert_assets → audit |
| `generate_bgm` | `…yukkuri.generateBgm` | fetch_topic → compose_bgm → insert_asset → audit |
| `render_video` | `…yukkuri.renderVideo` | build_timeline → render → update_status → audit |
| `review_video` | `…yukkuri.reviewVideo` | fetch_content → llm_review → update_status → social_publish → audit |

Plus the dispatch surface `server.cljc` (GRAPHS registry, NSID→assistant map,
camelCase→snake_case xrpc input coercion, `/ok` `/health` `/runs` `/xrpc`
routing + optional `x-api-key` guard) and the `audit.cljc` fire-and-forget BPMN
shim.

## Injectable boundaries (the actor-swap pattern)

Native / external effects are injectable dynamic vars (defaults documented in
each ns; tests rebind to stubs so the whole pipeline verifies offline under bb):

- **Persistence** → `store/*select-where*` `*insert-row*` `*query*`. The Python
  reaches `kotodama.kotoba_datomic`; this is the single kotoba-Datom-log seam.
  RisingWave is **not** reproduced (substrate boundary forbids it per CLAUDE.md;
  the Python's RW psycopg path maps onto this seam).
- **LLM** (scriptwriter + critic) → `llm/*chat-json*`, defaulting to the
  **Murakumo loopback gateway** (`http://127.0.0.1:4000/v1`) with a fleet
  allowlist guard (`assert-murakumo`, ADR-2605215000) instead of the RunPod
  vLLM proxy URL.
- **TTS** → `synthesize-voice/*tts-one*`, **image** → `generate-visual/*generate-one*`,
  **BGM** → `generate-bgm/*compose-bgm*`, **render** → `render-video/*render*`,
  **social** → `review-video/*social-publish*`, **audit** → `audit/*emit*`.
  Defaults use `babashka.http-client` + `cheshire`.

## Deviations from the Python (noted)

- **No `RetryPolicy`**: langgraph-clj has no per-node retry; the Python carried
  `RetryPolicy(max_attempts=…)` on most nodes. Behaviorally inert (retries only
  re-run on transient failure); recorded here for fidelity.
- **`render_video` build_timeline** drops the Python's 60 s RisingWave
  streaming-visibility poll loop — the kotoba Datom log is read-committed, so it
  reads once. The assembly logic + "no scenes → error" guard are identical.
- **Parallel fan-out** (`synthesize_voice`, `generate_visual`) uses `pmap`
  (clj analogue of `asyncio.gather`).
- **SSE `/runs/stream`** and **`/threads/{tid}/state`** (checkpointer snapshot)
  are not ported — they depend on the LangGraph checkpointer/stream runtime;
  the synchronous `/runs` + `/xrpc` invoke surface is the load-bearing port.

## Not ported (coexist, remain in Python only)

The 4 graphs that exist under `lg/lg_yukkuri/graphs/` but are **not registered**
in `lg/langgraph.json` / `server.py` (so not part of the deployed graph set):
`compose_scene`, `generate_character`, `translate_video`, `upload_youtube`.
Also `checkpointer.py`, `cron.py` (empty crons), `comfy_runner.py`,
`comfy_workflows.py` — runtime/native plumbing that the clj dispatch layer does
not need to reproduce to be faithful to the registered graph set.

## Run

```bash
bb test            # clojure.test suite (run_tests.clj)  → 36 tests / 99 assertions
bb run_tests.clj   # same
```
