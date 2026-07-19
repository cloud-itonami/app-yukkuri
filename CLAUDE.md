# etzhayyim-project-yukkuri — ゆっくり実況 (AI Yukkuri Video Generation)

共通ルールは `60-apps/CLAUDE.md` を参照。

## Overview

yukkuri.etzhayyim.com — ゆっくり実況動画を 1 トピック / 1 台本から自動生成する。画面は左右 2 キャラ構図 (L=Reimu-like / R=Marisa-like) を既定とし、`kokoro-ts` で TTS、背景・挿絵・SFX は murakumo image/audio、BGM は `ongakuka`、最終合成は `kami-engine` の headless render path を使って mp4 / webm に書き出す。

**依存**: `murakumo:inference/{text,image,audio}` / `ongakuka` / `kami-engine (kami-render + kami-audio + kami-character + kami-text + kami-scene-graph)` / `kokoro-ts` (40-engine に vendoring 予定)。

## Identifier (ADR-0019 atproto-native)

| 層 | 値 |
|---|---|
| Primary DID | `did:plc:yukkuri` (Phase 5 `plc.etzhayyim.com` で genesis) |
| Handle | `yukkuri.etzhayyim.com` |
| Legacy nanoid | `y5kk5r1x` (grandfather, deprecate 2026-10-01) |
| NSID | `com.etzhayyim.apps.yukkuri.*` |

## Project Actor Composition (1 project = N actor DIDs)

1 video = 1 project = 1 convoId。各 actor の中間生成物 (台本 / 音声 / 画像 / 効果音 / BGM / カット) は `projectId` でスコープ。

| Path DID | 役割 | 主モデル / 実装 |
|---|---|---|
| `did:web:yukkuri.etzhayyim.com` | controller / job orchestration | — |
| `did:web:yukkuri.etzhayyim.com:actor:scriptwriter` | トピック → 台本 (L/R 掛け合い, scene 分割, 感情タグ) | murakumo text (`gemma-4-12b-it` / `etzhayyim-moe-moe-kyun-general`) |
| `did:web:yukkuri.etzhayyim.com:actor:voiceLeft` | 左キャラ (Reimu-like) TTS | `kokoro-ts` voice preset `af_heart` 相当 |
| `did:web:yukkuri.etzhayyim.com:actor:voiceRight` | 右キャラ (Marisa-like) TTS | `kokoro-ts` voice preset `am_puck` 相当 |
| `did:web:yukkuri.etzhayyim.com:actor:character` | 立ち絵 pose / 表情 / 口パク timing 生成 | `kami-character` + `kami-skeleton` / VRM part compose |
| `did:web:yukkuri.etzhayyim.com:actor:illustrator` | 背景 + 挿絵 + テロップ素材 | `murakumo:inference/image` (SDXL / flux) |
| `did:web:yukkuri.etzhayyim.com:actor:sfx` | 効果音選定 + 必要時生成 | SFX lib + `murakumo:inference/audio` (sfx-mode) |
| `did:web:yukkuri.etzhayyim.com:actor:composer` | BGM (cross-project invoke `ongakuka.compose`) | `com.etzhayyim.ongakuka.compose` |
| `did:web:yukkuri.etzhayyim.com:actor:editor` | timeline / cut / fade / telop / subtitle 合成仕様 | local TS scheduler |
| `did:web:yukkuri.etzhayyim.com:actor:renderer` | 最終動画 render (frame → H.264/VP9) | `kami-engine` headless + `ffmpeg-wasm` mux |
| `did:web:yukkuri.etzhayyim.com:actor:critic` | 尺 / ラウドネス / IP / 表現 QA | text+audio classifier |

actor 間連携は **convo chat (`sendProjectMessage`)** + AT Record commit。中間成果物は `com.etzhayyim.apps.yukkuri.asset` + `actorDid` / `kind` field で帰属。

## Domain Model

| 概念 | NSID | Graph node |
|---|---|---|
| 動画 (1 project) | `com.etzhayyim.apps.yukkuri.video` | `YkVideo` |
| シーン (順序付き切り出し単位) | `com.etzhayyim.apps.yukkuri.scene` | `YkScene` |
| セリフ (speaker + text + TTS blob) | `com.etzhayyim.apps.yukkuri.line` | `YkLine` |
| アセット (image / sfx / bgm / vrm) | `com.etzhayyim.apps.yukkuri.asset` | `YkAsset` |
| 生成イベント (audit + metering) | `com.etzhayyim.apps.yukkuri.generation` | `YkGeneration` |

### Edge predicates

| Predicate | Domain → Range |
|---|---|
| `HAS_SCENE` | YkVideo → YkScene |
| `HAS_LINE` | YkScene → YkLine |
| `USES_ASSET` | YkScene → YkAsset |
| `VOICED_BY` | YkLine → (actor DID) |
| `PRODUCED_BY` | YkAsset → (actor DID) |
| `GENERATED_BY` | YkVideo → YkGeneration |
| `REGENERATED_FROM` | YkGeneration → YkGeneration (lineage) |

## XRPC Surface

| NSID | Type | 用途 |
|---|---|---|
| `com.etzhayyim.apps.yukkuri.compose` | procedure | topic/outline から 1 video を enqueue (returns videoUri 即時) |
| `com.etzhayyim.apps.yukkuri.regenerate` | procedure | scene / line / asset 部分再生成 |
| `com.etzhayyim.apps.yukkuri.render` | procedure | 全素材揃ったらフル video を render (mp4/webm) |
| `com.etzhayyim.apps.yukkuri.listVideos` | query | offset/limit list |
| `com.etzhayyim.apps.yukkuri.getVideo` | query | video + scenes + lines + assets + last generation |
| `com.etzhayyim.apps.yukkuri.health` | procedure | health probe (bootstrap) |

## Triggers (kotodama.jsonld 予定)

```jsonc
{
  "triggers": {
    "subscribeRepos": {
      "collections": [
        "app.bsky.feed.post",
        "app.bsky.feed.like",
        "app.bsky.feed.repost",
        "app.bsky.graph.follow",
        "com.etzhayyim.apps.yukkuri.video",
        "com.etzhayyim.apps.yukkuri.scene",
        "com.etzhayyim.apps.yukkuri.line",
        "com.etzhayyim.apps.yukkuri.asset",
        "com.etzhayyim.apps.yukkuri.generation"
      ]
    }
  }
}
```

## Reactive Pipeline (Design E 3-Tier Write)

```
XRPC compose
  → handleAietzhayyimAppsYukkuriCompose
    → ComAtprotoRepoCreateRecord("video", {status:"queued", projectId, topic, ...})
       ↓ onCommit (subscribeRepos: com.etzhayyim.apps.yukkuri.video)
       handleAietzhayyimAppsYukkuriVideo:
         video.status === "queued" →
           scriptwriter.draft() → scenes[]+lines[] records (T2) → status "script"
         video.status === "script" →
           parallel(
             voiceLeft.synthesize(lines.left)  → line.voiceBlobKey,
             voiceRight.synthesize(lines.right) → line.voiceBlobKey,
             illustrator.paint(scenes)          → asset(image),
             sfx.pick(scenes)                   → asset(sfx),
             composer.compose()                 → asset(bgm) via ongakuka.compose,
             character.pose(lines)              → asset(character-sequence)
           ) → status "assembled"
         video.status === "assembled" →
           editor.layout() → timeline asset (json) → status "editready"
         video.status === "editready" →
           renderer.render() → final mp4/webm blob, video.blobKey set
         video.status === "rendered" →
           critic.review() → status "published" or "rejected"
         video.status === "published" →
           [DERIVED] AppBskyFeedPost (T1 social, public release)
```

| Tier | 内容 | API |
|---|---|---|
| **T1 Social** | 「新作ゆっくり: {title} 🎬 {videoUri}」 | `app.bsky.feed.post` (derived from video→published) |
| **T2 Domain** | video / scene / line / asset / generation | `com.atproto.repo.createRecord` → RisingWave `vertex_yukkuri_*` |
| **T3 State** | 台本下書き / voice preset 設定 / quota / private prompts | `Preferences()` |
| **Blob** | wav (voice) / png-jpg (image) / wav-ogg (sfx/bgm) / mp4-webm (final) | `uploadBlob` (SHA-256 content-addressed B2)、`blobKey` 経由参照 |

## LLM Routing (CRITICAL)

**App Workers must call LLM via `llmCall`/`agentConverseAsync` from `@etzhayyim/kotodama-host-sdk`.** Direct `fetch()` to `llm.etzhayyim.com` or the Linode Ollama IP (`172.236.133.64`) returns 403 (empty body) from CF WAF for same-account Worker outbound subrequests — confirmed 2026-04-15.

| Path | Result | Use |
|---|---|---|
| `llmCall(system, user)` (host-sdk) | ✅ 200 | **DEFAULT** — routes via `PDS_SERVICE` binding → `atproto.etzhayyim.com/xrpc/com.etzhayyim.apps.llm.chatCompletions` → `MURAKUMO_SERVICE` (Worker binding, WAF bypass) |
| `fetch("https://llm.etzhayyim.com/...")` | ❌ 403 CF WAF | Direct outbound — **禁止** |
| `fetch("http://172.236.133.64/...")` | ❌ 403 CF WAF | Direct outbound — **禁止** |

## Inference Backends

| Stage | Provider call | Model / Runtime (Phase 0) |
|---|---|---|
| scriptwriter | `murakumo:inference/text` chat-completions | `gemma-4-12b-it` / `qwen3.5-9b` |
| voiceLeft / voiceRight | `kokoro-ts` inline (edge) or `murakumo:inference/audio` tts | `Kokoro-82M` (ONNX via `kokoro-ts`) |
| illustrator | `murakumo:inference/image` text-to-image | `flux-schnell` / `sdxl-turbo-ja-lora` |
| sfx | library lookup + `murakumo:inference/audio` sfx-gen (fallback) | `audiogen-1b` |
| composer | `com.etzhayyim.ongakuka.compose` (cross-project) | ongakuka pipeline |
| character | `kami-character` (WASM, client-side at render time) | kami-engine crates |
| editor | local TS (Worker) — timeline JSON 生成 | — |
| renderer | `kami-engine` headless render → frames → `ffmpeg-wasm` mux | wgpu (`Backends::BROWSER_WEBGPU`) in CF Worker Durable Object with browser rendering binding OR Mac render pool |
| critic | text classifier + loudness (EBU R128) + IP sim | local TS |

### `kokoro-ts` の位置付け

- 1st choice: `kokoro-ts` を `40-engine/kokoro-ts/` (または `10-protocol` 配下) に vendoring し、Worker (edge) または render pool Mac で直接実行。Kokoro-82M は ONNX で軽量 (<100MB) 、低レイテンシで左右 2 話者分を現実的
- Fallback: `murakumo:inference/audio` に `tts/kokoro` provider を追加 (既存 `serve_plain.py` 拡張)
- Voice preset は `line.voicePreset` field で指定 (`af_heart` / `am_puck` / ...)。SSML 風感情タグ (`{emotion:"surprised"}`) は scriptwriter が出力し TTS パラメタに変換

### Render backend の選択

| 方式 | Pros | Cons | Phase |
|---|---|---|---|
| **Mac render pool** (murakumo fleet 4 node) | GPU/MLX 既存、kami-engine native (Metal) 高速 | 4 node 共有、長時間 job で queue | **Phase 0 DEFAULT** |
| CF Browser Rendering binding (headless Chromium + WebGPU) | serverless, auto-scale | WebGPU 解像度/duration 制限、ffmpeg 外出し | Phase 1 複数 job 並列時 |
| 自前 Render DO (WASM kami-render + wasm-ffmpeg) | edge 超分散 | WebGPU in DO 未成熟 | Phase 2 |

**Phase 進行**:
1. **Phase 0** (MVP): Mac render pool に `yukkuri-renderer` service を追加 (`serve_plain.py` 流、kami-engine CLI を child process で起動)。台本 → 音声 → 画像 → kami-engine に JSON timeline 投入 → mp4 出力 → B2 に blob 登録
2. **Phase 1**: CF Browser Rendering で短尺 (<60s, 720p) を並列化、長尺は Mac fleet に降ろす dispatcher を追加
3. **Phase 2**: `kokoro-ts` 日本語 voice pack fine-tune (権利クリアな録音 corpus のみ) + 自前立ち絵モデル (VRM 動的合成) + lip-sync 精度 UP

## CRITICAL: Copyright / Consent / 表現 Invariants

1. **キャラクター資産**: 東方 Project (ZUN) の二次創作 GL の範囲内でのみ利用。公式立ち絵/素材を直接学習/再配布しない。付属立ち絵は **etzhayyim 独自デザインの reimu-like / marisa-like オリジナルキャラ** を default とし、名前・衣装も差分化する (既定 displayName: `霊夢` ではなく `ゆきり` / `まりり` 等の独自名)
2. **音声**: `kokoro-ts` 既定 voice は permissive license のもののみ採用。商用利用不可 voice は UI で明示 disabled
3. **BGM / SFX**: `ongakuka` 経由は copyright invariants を継承 (CLAP cosine > 0.92 は reject)。SFX lib は CC0 / CC-BY のみ
4. **画像**: `illustrator` は style prompt に実在作家名を指定された場合 reject。reference image アップロードは `license ∈ {permissive, own, licensed}` のみ
5. **台本**: 個人名 / 連絡先 / 誹謗中傷検出で critic reject、または T3 private only
6. **Lip-sync データ**: 音素 → 口形素 mapping は kokoro phoneme 出力から導出 (OSS)。外部 FaceRig 等は使わない
7. **Output watermark**: final mp4 に不可視 watermark (video-watermark, Phase 1)。`generation.video_hash` は Iceberg archive に記録
8. **Deep fake / なりすまし禁止**: 実在人物音声 / 顔 / ロゴの模倣は critic で自動検出 → reject

## Cross-Project Dependencies

| Project | 関係 |
|---|---|
| `murakumo` | `inference/{text,image,audio}` provider (CRITICAL) |
| `ongakuka` | BGM 生成 (cross-project invoke `com.etzhayyim.ongakuka.compose`) |
| `kakin` | quota check (per compose / per render) |
| `credits` | consumer spend / operator reward (GPU 秒・render 時間) |
| `auth` | per-call ES256 Service Auth (`lxm=com.etzhayyim.apps.yukkuri.compose` etc.) |
| `signal` | 台本下書き / private reference image の field encrypt |
| `vault` | licensed voice pack / 立ち絵素材の zero-knowledge 保管 |
| `well-becoming` | critic の配慮スコア反映 |

## App Component (TS Native)

| Key | Value |
|---|---|
| Nanoid | `y5kk5r1x` |
| Folder | `60-apps/etzhayyim-project-yukkuri/wasm/etzhayyim-wasm-yukkuri-y5kk5r1x/` |
| Runtime | TS Native (`src/app.ts`, `"runtimeType": "worker"`) |
| Wrangler route | `yukkuri.etzhayyim.com/*` |
| Bindings | `HYPERDRIVE`, `R2_BLOBS`, `MURAKUMO_SERVICE`, `ONGAKUKA_SERVICE`, `AUTH_SERVICE`, `PDS_SERVICE`, `KAKIN_SERVICE`, `CREDITS_SERVICE`, `HEADLESS_BROWSER` (Phase 1) |

## Frontend (planned)

- Hono router + Svelte CSR (flat west Svelte packages)
- 画面:
  - topic → outline editor (scene 追加/削除/ドラッグ並び替え)
  - per-scene dialogue editor (L/R speaker toggle + 感情タグ)
  - voice preset picker + 試聴
  - style preset (背景 / 立ち絵 / BGM ジャンル)
  - generation queue + preview player (scene 単位 / full video)
  - timeline ruler (waveform + telop + 口パク dots)
  - render 押下で final mp4 DL
- Deep-link: `https://yukkuri.etzhayyim.com/at/{handle}/com.etzhayyim.apps.yukkuri.video/{rkey}`

## Migration Backlog

| 項目 | 状態 |
|---|---|
| Lexicon JSON × 6 (`00-contracts/lexicons/com/etzhayyim/apps/yukkuri/`) | DONE (2026-04-15) |
| `30-graph/graph-schema/migrations/0059_vertex_yukkuri.ts` | DONE (2026-04-15) |
| Murakumo `inference/audio` tts provider (kokoro) spec 追記 | TODO |
| `kokoro-ts` vendoring (`40-engine/kokoro-ts/`) | TODO |
| `kotodama.jsonld` + `src/app.ts` + `wrangler.jsonc` (T1 worker) | DONE (2026-04-15) |
| Mac render pool: `yukkuri-renderer` service (`serve_plain.py` + kami-engine CLI) | TODO |
| `kami-engine` headless render CLI (timeline.json → mp4) | TODO |
| `70-tools/etzhayyim/yukkuri.go` CLI subcommand (`etzhayyim yukkuri compose/render/list/get`) | TODO |
| `[[projects]]` / `[[mitama_actors]]` / `[[legacy_nanoids]]` 登録 (`deps.toml`) | TODO |
| Phase 1 独自立ち絵セット (GL-clean) 用意 | TODO |
| Phase 1 CF Browser Rendering dispatcher | TODO |
