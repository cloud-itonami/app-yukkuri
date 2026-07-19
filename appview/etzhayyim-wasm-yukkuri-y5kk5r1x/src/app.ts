import {
  asAgentTool,
  createKyselyDb,
  createWorkerExport,
  decodeJson,
  genID,
  nowISO,
  nsid,
  withCapabilityTags,
  type HostSDK,
} from "@etzhayyim/kotodama-host-sdk";

/** AT Protocol TID generator (base32 s32 charset, 13 chars). */
const S32 = "234567abcdefghijklmnopqrstuvwxyz";
let _lastTidTs = 0n;
function generateLocalTid(): string {
  let ts = BigInt(Date.now()) * 1000n; // µs since epoch
  if (ts <= _lastTidTs) ts = _lastTidTs + 1n;
  _lastTidTs = ts;
  // Pack: upper 53 bits = timestamp, lower 10 bits = clock_id (0), bit 0 = 0
  const n = (ts << 11n);
  let out = "";
  let v = n;
  for (let i = 0; i < 13; i++) {
    out = S32[Number(v & 0x1fn)] + out;
    v >>= 5n;
  }
  return out;
}

const DEFAULT_TARGET_SEC = 120;
const COMPOSE_NSID = "com.etzhayyim.apps.yukkuri.compose";

const PRIMARY_PATH = "did:web:yukkuri.etzhayyim.com";
const PATH_SCRIPTWRITER = "did:web:yukkuri.etzhayyim.com:actor:scriptwriter";
const PATH_VOICE_LEFT = "did:web:yukkuri.etzhayyim.com:actor:voiceLeft";
const PATH_VOICE_RIGHT = "did:web:yukkuri.etzhayyim.com:actor:voiceRight";
const PATH_CHARACTER = "did:web:yukkuri.etzhayyim.com:actor:character";
const PATH_ILLUSTRATOR = "did:web:yukkuri.etzhayyim.com:actor:illustrator";
const PATH_SFX = "did:web:yukkuri.etzhayyim.com:actor:sfx";
const PATH_COMPOSER = "did:web:yukkuri.etzhayyim.com:actor:composer";
const PATH_EDITOR = "did:web:yukkuri.etzhayyim.com:actor:editor";
const PATH_RENDERER = "did:web:yukkuri.etzhayyim.com:actor:renderer";
const PATH_CRITIC = "did:web:yukkuri.etzhayyim.com:actor:critic";

const ALL_PATHS: Array<[string, string, string]> = [
  ["", "Yukkuri", "AI yukkuri video generator (controller)"],
  ["actor:scriptwriter", "Scriptwriter", "Dialogue script (L/R) LLM"],
  ["actor:voiceLeft", "Voice Left", "kokoro-ts TTS for left character"],
  ["actor:voiceRight", "Voice Right", "kokoro-ts TTS for right character"],
  ["actor:character", "Character", "立ち絵 pose / expression / lip-sync"],
  ["actor:illustrator", "Illustrator", "Background + insert image generation"],
  ["actor:sfx", "SFX", "Sound-effect selection / generation"],
  ["actor:composer", "Composer", "BGM via ongakuka.compose"],
  ["actor:editor", "Editor", "Timeline assembly"],
  ["actor:renderer", "Renderer", "kami-engine headless mp4/webm render"],
  ["actor:critic", "Critic", "尺 / loudness / IP / 表現 QA"],
];

let pathsRegistered = false;
async function ensurePathDids(sdk: HostSDK): Promise<void> {
  if (pathsRegistered) return;
  for (const [path, displayName, description] of ALL_PATHS) {
    sdk.pds.dispatch({ type: "com.atproto.identity.create", payload: { path, displayName, description } });
  }
  pathsRegistered = true;
}



type ComposeInput = {
  title?: string;
  topic?: string;
  outline?: string;
  language?: string;
  targetSec?: number;
  resolution?: string;
  fps?: number;
  voiceLeft?: string;
  voiceRight?: string;
  styleImage?: string;
  bgmStyle?: string;
  seed?: number;
  autoRender?: boolean;
};

// CF Workers intercepts fetch() to same-zone hostnames (dispatcher.etzhayyim.com → 1003/1033).
// Use bpmn-dispatcher Vultr LB IP directly (DISPATCHER_URL env override takes precedence).
const DISPATCHER_ORIGINS = [
  "http://66.42.104.29",
];

function dispatcherUrl(env: Record<string, unknown>): string {
  return String(env["DISPATCHER_URL"] || "");
}

async function triggerBpmnPipeline(
  env: Record<string, unknown>,
  videoUri: string,
  voiceLeft: string,
  voiceRight: string,
  meta?: {
    title: string;
    topic: string;
    language: string;
    targetSec: number;
    resolution: string;
    fps: number;
    seed: number;
    projectId: string;
  },
): Promise<void> {
  const body = JSON.stringify({ videoUri, voiceLeft, voiceRight, ...meta });
  const internalSecret = String(env["DISPATCHER_INTERNAL_SECRET"] || "");
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (internalSecret) headers["x-internal-trust"] = internalSecret;
  // If DISPATCHER_URL override is set (e.g. comfyui proxy), use it.
  const overrideUrl = dispatcherUrl(env);
  const origins = overrideUrl ? [overrideUrl] : DISPATCHER_ORIGINS;
  let lastErr: unknown = null;
  for (const origin of origins) {
    try {
      const resp = await fetch(`${origin}/xrpc/${COMPOSE_NSID}`, {
        method: "POST",
        headers,
        body,
        signal: AbortSignal.timeout(15_000),
      });
      if (resp.status < 500) return;
    } catch (err) {
      lastErr = err;
      console.error("[yukkuri] BPMN dispatch failed on", origin, String((err as Error).message));
    }
  }
  if (lastErr) console.error("[yukkuri] BPMN pipeline all origins failed:", String((lastErr as Error).message));
}

async function cmdCompose(sdk: HostSDK, env: Record<string, unknown>, body: Uint8Array): Promise<string> {
  const input = decodeJson(body, {
    title: "",
    topic: "",
    outline: "",
    language: "ja",
    targetSec: 0,
    resolution: "1080p",
    fps: 30,
    voiceLeft: "",
    voiceRight: "",
    styleImage: "",
    bgmStyle: "",
    seed: 0,
    autoRender: true,
  }) as ComposeInput;

  const topic = String(input.topic || "").trim();
  if (!topic) return JSON.stringify({ error: "topic is required" });

  const title = String(input.title || "").trim() || `ゆっくり実況: ${topic.slice(0, 48)}`;
  const language = String(input.language || "ja");
  const targetSec = Math.min(1800, Math.max(15, Number(input.targetSec) || DEFAULT_TARGET_SEC));
  const resolution = String(input.resolution || "1080p");
  const fps = Number(input.fps) || 30;
  const voiceLeft = String(input.voiceLeft || "af_heart");
  const voiceRight = String(input.voiceRight || "am_puck");
  const seed = Number(input.seed) || 0;

  // CF Worker is a thin L3 dispatcher: validate input, generate identifiers, trigger BPMN.
  // All DB writes (vertex_yukkuri_video initial record) are handled by yukkuri.scene.persist
  // LangServer task — no CF 25s timeout, no Hyperdrive INSERT here.
  // Social post is emitted by yukkuri.social.post BPMN task after critic review.
  const projectId = `proj-${genID("yk")}`;

  const videoRkey = generateLocalTid();
  const videoRepo = (sdk.pds as any).selfRepo as string || "did:web:y5kk5r1x.etzhayyim.com";
  const videoUri = `at://${videoRepo}/com.etzhayyim.apps.yukkuri.video/${videoRkey}`;

  // Ensure path DIDs are registered (fire-and-forget).
  void ensurePathDids(sdk);
  void PATH_CHARACTER; void PATH_ILLUSTRATOR; void PATH_SFX; void PATH_COMPOSER; void PATH_EDITOR; void PATH_RENDERER; void PATH_CRITIC; void PRIMARY_PATH;

  // Trigger LangServer BPMN-contract pipeline — passes full metadata so scene.persist can create the
  // initial DB record without needing a prior Hyperdrive INSERT.
  void triggerBpmnPipeline(env, videoUri, voiceLeft, voiceRight, {
    title,
    topic,
    language,
    targetSec,
    resolution,
    fps,
    seed,
    projectId,
  });

  return JSON.stringify({
    videoUri,
    videoRkey,
    projectId,
    status: "queued",
    scriptSource: "bpmn:pending",
    estimatedSec: targetSec,
    autoRender: Boolean(input.autoRender),
    // Descriptive only — dispatch itself is a plain HTTP POST to the
    // dispatcher XRPC origin (see triggerBpmnPipeline below), not a Zeebe
    // client call. Label kept BPMN-generic since Zeebe was decommissioned
    // (50-infra/vultr/zeebe removed, ADR-2607071500): its VKE host cluster
    // was permanently deleted 2026-06-24/25.
    pipeline: "bpmn:yukkuriCompose — scene.persist(create+LLM) → voice → image → assemble → critic → social.post",
  });
}

async function cmdRegenerate(sdk: HostSDK, body: Uint8Array): Promise<string> {
  const input = decodeJson(body, { videoUri: "", target: "", sceneRkey: "", lineRkey: "", assetRkey: "", instructions: "", seed: 0 });
  const videoUri = String(input.videoUri || "").trim();
  const target = String(input.target || "").trim();
  if (!videoUri || !target) return JSON.stringify({ error: "videoUri and target are required" });
  const genRkey = genID("gen");
  const generationRecord = {
    targetUri: videoUri,
    stage: `regen:${target}`,
    actorDid: PATH_SCRIPTWRITER,
    modelId: "gemma4:e2b",
    params: JSON.stringify({ target, sceneRkey: input.sceneRkey || "", lineRkey: input.lineRkey || "", assetRkey: input.assetRkey || "", instructions: String(input.instructions || "").slice(0, 400), seed: Number(input.seed) || 0 }),
    status: "queued",
    createdAt: nowISO(),
  };
  const db = createKyselyDb((sdk.env as any).HYPERDRIVE) as any;
  await db.insertInto("vertex_yukkuri_generation").values({
    vertex_id: `at://${PATH_SCRIPTWRITER}/com.etzhayyim.apps.yukkuri.generation/${genRkey}`,
    sensitivity_ord: 2,
    owner_did: PATH_SCRIPTWRITER,
    target_uri: generationRecord.targetUri,
    stage: generationRecord.stage,
    actor_did: generationRecord.actorDid,
    model_id: generationRecord.modelId,
    params: generationRecord.params,
    status: generationRecord.status,
    created_at: generationRecord.createdAt,
    actor_id: "y5kk5r1x",
  }).execute();
  return JSON.stringify({
    generationUri: `at://yukkuri.etzhayyim.com/com.etzhayyim.apps.yukkuri.generation/${genRkey}`,
    status: "queued",
    note: "Phase 0 stub — regeneration executed on next reactive tick",
  });
}

async function cmdRender(sdk: HostSDK, body: Uint8Array): Promise<string> {
  const input = decodeJson(body, { videoUri: "", container: "mp4", videoCodec: "h264", audioCodec: "aac", resolution: "", fps: 0, loudnessLufs: -14.0, watermark: true, backend: "auto" });
  const videoUri = String(input.videoUri || "").trim();
  if (!videoUri) return JSON.stringify({ error: "videoUri is required" });
  const generationRecord = {
    targetUri: videoUri,
    stage: "render",
    actorDid: PATH_RENDERER,
    modelId: "kami-engine-headless",
    params: JSON.stringify({ container: input.container, videoCodec: input.videoCodec, audioCodec: input.audioCodec, resolution: input.resolution || "", fps: Number(input.fps) || 0, loudnessLufs: Number(input.loudnessLufs) || -14.0, watermark: Boolean(input.watermark), backend: input.backend }),
    renderBackend: String(input.backend || "auto"),
    status: "queued",
    createdAt: nowISO(),
  };
  const renderRkey = genID("render");
  const db = createKyselyDb((sdk.env as any).HYPERDRIVE) as any;
  await db.insertInto("vertex_yukkuri_generation").values({
    vertex_id: `at://${PATH_RENDERER}/com.etzhayyim.apps.yukkuri.generation/${renderRkey}`,
    sensitivity_ord: 2,
    owner_did: PATH_RENDERER,
    target_uri: generationRecord.targetUri,
    stage: generationRecord.stage,
    actor_did: generationRecord.actorDid,
    model_id: generationRecord.modelId,
    params: generationRecord.params,
    render_backend: generationRecord.renderBackend,
    status: generationRecord.status,
    created_at: generationRecord.createdAt,
    actor_id: "y5kk5r1x",
  }).execute();
  return JSON.stringify({
    videoUri,
    status: "queued",
    estimatedSec: 180,
    note: "Phase 0: render job enqueued to Mac render pool (kami-engine headless + ffmpeg mux). Blob will attach on completion.",
  });
}

type VideoRow = {
  vertex_id: string; title?: string | null; topic?: string | null; status?: string | null;
  language?: string | null; target_sec?: number | null; duration_sec?: number | null;
  resolution?: string | null; fps?: number | null; blob_key?: string | null; mime_type?: string | null;
  project_id?: string | null; owner_did?: string | null; created_at?: string | null;
  voice_left?: string | null; voice_right?: string | null; script_source?: string | null;
  scenes_json?: string | null; scene_count?: number | null; line_count?: number | null;
  render_blob_key?: string | null; render_url?: string | null;
};
type SceneRow = {
  vertex_id: string; video_uri?: string | null; idx?: number | null; start_sec?: number | null;
  duration_sec?: number | null; summary?: string | null; background_asset_uri?: string | null; bgm_asset_uri?: string | null;
};
type LineRow = {
  vertex_id: string; video_uri?: string | null; scene_uri?: string | null; idx?: number | null;
  speaker?: string | null; text?: string | null; emotion?: string | null; voice_preset?: string | null;
  voice_blob_key?: string | null; duration_sec?: number | null;
};
type AssetRow = {
  vertex_id: string; video_uri?: string | null; scene_uri?: string | null; kind?: string | null;
  blob_key?: string | null; mime_type?: string | null; actor_did?: string | null;
};
type GenerationRow = {
  vertex_id: string; target_uri?: string | null; stage?: string | null; status?: string | null; created_at?: string | null;
};
type SceneTabRow = { scene_index: number; location: string | null; action: string | null };
type LineTabRow = { scene_index: number; line_index: number; speaker: string | null; text: string | null; emotion: string | null };

function videoUriToRkey(videoUri: string): string {
  const m = videoUri.match(/\/com\.etzhayyim\.apps\.yukkuri\.video\/([^/]+)$/);
  return m ? m[1] : videoUri;
}

function normalizeVideoUri(rkeyOrUri: string): string {
  const rkey = videoUriToRkey(rkeyOrUri);
  return `at://yukkuri.etzhayyim.com/com.etzhayyim.apps.yukkuri.video/${rkey}`;
}

function yukkuriDb(env: Record<string, unknown>): any {
  return createKyselyDb((env as any).HYPERDRIVE) as any;
}

async function listVideoRows(env: Record<string, unknown>, filters: { ownerDid: string; status: string; limit: number; offset: number }): Promise<VideoRow[]> {
  let query = yukkuriDb(env).selectFrom("vertex_yukkuri_video").selectAll();
  if (filters.ownerDid) query = query.where("owner_did", "=", filters.ownerDid);
  if (filters.status) query = query.where("status", "=", filters.status);
  return await query.orderBy("created_at", "desc").limit(filters.limit).offset(filters.offset).execute() as VideoRow[];
}

async function findVideoRow(env: Record<string, unknown>, rkey: string): Promise<VideoRow | undefined> {
  const db = yukkuriDb(env);
  return (
    await db.selectFrom("vertex_yukkuri_video").selectAll().where("vertex_id", "=", rkey).limit(1).executeTakeFirst()
    ?? await db.selectFrom("vertex_yukkuri_video").selectAll().where("vertex_id", "like", `%/${rkey}`).limit(1).executeTakeFirst()
  ) as VideoRow | undefined;
}

async function listAssetRows(env: Record<string, unknown>, videoUri: string): Promise<AssetRow[]> {
  return await yukkuriDb(env).selectFrom("vertex_yukkuri_asset").selectAll().where("video_uri", "=", videoUri).execute() as AssetRow[];
}

async function latestGenerationRow(env: Record<string, unknown>, videoUri: string): Promise<GenerationRow | undefined> {
  return await yukkuriDb(env)
    .selectFrom("vertex_yukkuri_generation")
    .selectAll()
    .where("target_uri", "=", videoUri)
    .orderBy("created_at", "desc")
    .limit(1)
    .executeTakeFirst() as GenerationRow | undefined;
}

async function listSceneTabRows(env: Record<string, unknown>, rkey: string): Promise<SceneTabRow[]> {
  return await yukkuriDb(env)
    .selectFrom("vertex_yukkuri_scene")
    .select(["scene_index", "location", "action"])
    .where("video_id", "=", rkey)
    .orderBy("scene_index")
    .limit(20)
    .execute() as SceneTabRow[];
}

async function listLineTabRows(env: Record<string, unknown>, rkey: string): Promise<LineTabRow[]> {
  return await yukkuriDb(env)
    .selectFrom("vertex_yukkuri_line")
    .select(["scene_index", "line_index", "speaker", "text", "emotion"])
    .where("video_id", "=", rkey)
    .orderBy("scene_index")
    .orderBy("line_index")
    .limit(500)
    .execute() as LineTabRow[];
}

async function cmdListVideos(_sdk: HostSDK, env: Record<string, unknown>, body: Uint8Array): Promise<string> {
  const input = decodeJson(body, { ownerDid: "", status: "", offset: 0, limit: 50 });
  const offset = Math.max(0, Number(input.offset) || 0);
  const limit = Math.min(200, Math.max(1, Number(input.limit) || 50));
  const ownerDid = String(input.ownerDid || "").trim();
  const status = String(input.status || "").trim();
  try {
    const rows = await listVideoRows(env, { ownerDid, status, limit, offset });
    const videos = rows.map((r) => ({
      videoUri: normalizeVideoUri(r.vertex_id),
      projectId: r.project_id ?? "",
      title: r.title ?? "",
      topic: r.topic ?? "",
      status: r.status ?? "",
      sceneCount: r.scene_count ?? 0,
      lineCount: r.line_count ?? 0,
      durationSec: r.duration_sec ?? 0,
      blobKey: r.blob_key ?? "",
      createdAt: r.created_at ?? "",
    }));
    return JSON.stringify({ videos, total: videos.length, offset, limit });
  } catch (err) {
    return JSON.stringify({ videos: [], total: 0, offset, limit, error: String((err as Error).message).slice(0, 200) });
  }
}

async function cmdGetVideo(_sdk: HostSDK, env: Record<string, unknown>, body: Uint8Array): Promise<string> {
  const input = decodeJson(body, { videoUri: "" });
  const videoUri = String(input.videoUri || "").trim();
  if (!videoUri) return JSON.stringify({ error: "videoUri is required" });
  const rkey = videoUriToRkey(videoUri);
  try {
    const videoRow = await findVideoRow(env, rkey);
    if (!videoRow) return JSON.stringify({ error: "not found", videoUri });
    const canonicalUri = normalizeVideoUri(videoRow.vertex_id);

    let embeddedScenes: Array<{ idx: number; summary: string; durationSec: number; lines: Array<{ idx: number; speaker: string; text: string; emotion: string; voicePreset: string; voicedBy: string }> }> = [];
    try {
      if (videoRow.scenes_json) embeddedScenes = JSON.parse(videoRow.scenes_json);
    } catch { /* fall through */ }

    const [assetRows, genRow] = await Promise.all([
      listAssetRows(env, canonicalUri),
      latestGenerationRow(env, canonicalUri),
    ]);

    let scenes: Array<{ index: number; location?: string; action?: string; summary: string; durationSec: number }> = [];
    let lines: Array<{ sceneIndex: number; index: number; speaker: string; text: string; emotion: string; voicePreset: string; voicedBy: string }> = [];
    if (embeddedScenes.length > 0) {
      scenes = embeddedScenes.map((s) => ({ index: s.idx, summary: s.summary ?? "", durationSec: s.durationSec ?? 0 }));
      for (const s of embeddedScenes) {
        for (const l of s.lines ?? []) {
          lines.push({ sceneIndex: s.idx, index: l.idx, speaker: l.speaker, text: l.text, emotion: l.emotion, voicePreset: l.voicePreset, voicedBy: l.voicedBy });
        }
      }
    } else {
      try {
        const [sceneTabRows, lineTabRows] = await Promise.all([
          listSceneTabRows(env, rkey),
          listLineTabRows(env, rkey),
        ]);
        scenes = sceneTabRows.map((s) => ({ index: s.scene_index, location: s.location ?? undefined, action: s.action ?? undefined, summary: s.location ?? "", durationSec: 0 }));
        lines = lineTabRows.map((l) => ({ sceneIndex: l.scene_index, index: l.line_index, speaker: l.speaker ?? "left", text: l.text ?? "", emotion: l.emotion ?? "normal", voicePreset: "", voicedBy: "" }));
      } catch { /* ignore: tables may not exist */ }
    }

    return JSON.stringify({
      video: {
        videoUri: canonicalUri,
        projectId: videoRow.project_id ?? "",
        title: videoRow.title ?? "",
        topic: videoRow.topic ?? "",
        status: videoRow.status ?? "",
        language: videoRow.language ?? "",
        targetSec: videoRow.target_sec ?? 0,
        durationSec: videoRow.duration_sec ?? 0,
        resolution: videoRow.resolution ?? "",
        fps: videoRow.fps ?? 0,
        voiceLeft: videoRow.voice_left ?? "",
        voiceRight: videoRow.voice_right ?? "",
        scriptSource: videoRow.script_source ?? "",
        sceneCount: videoRow.scene_count ?? 0,
        lineCount: videoRow.line_count ?? 0,
        blobKey: videoRow.blob_key ?? "",
        mimeType: videoRow.mime_type ?? "",
        renderBlobKey: videoRow.render_blob_key ?? "",
        renderUrl: videoRow.render_url ?? "",
        createdAt: videoRow.created_at ?? "",
      },
      scenes,
      lines,
      assets: assetRows.map((r) => ({
        assetUri: `at://yukkuri.etzhayyim.com/com.etzhayyim.apps.yukkuri.asset/${r.vertex_id}`,
        kind: r.kind ?? "",
        blobKey: r.blob_key ?? "",
        mimeType: r.mime_type ?? "",
        actorDid: r.actor_did ?? "",
      })),
      lastGeneration: genRow ? {
        generationUri: `at://yukkuri.etzhayyim.com/com.etzhayyim.apps.yukkuri.generation/${genRow.vertex_id}`,
        stage: genRow.stage ?? "",
        status: genRow.status ?? "",
        createdAt: genRow.created_at ?? "",
      } : undefined,
    });
  } catch (err) {
    return JSON.stringify({ error: String((err as Error).message).slice(0, 200), videoUri });
  }
}

function renderVideoHtml(
  rkey: string,
  video: VideoRow | null,
  embeddedScenes: Array<{ idx: number; summary: string; durationSec: number; lines: Array<{ idx: number; speaker: string; text: string; emotion: string }> }>,
): string {
  if (!video) {
    return `<!doctype html><html lang="ja"><head><meta charset="utf-8"><title>Not Found — Yukkuri</title><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font-family:sans-serif;background:#0f0f1a;color:#e0e0e0;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}p{font-size:1.2rem}</style></head><body><p>動画が見つかりません: ${rkey}</p></body></html>`;
  }
  const statusColor: Record<string, string> = { published: "#4caf50", queued: "#ff9800", processing: "#2196f3", rejected: "#f44336" };
  const statusBg = statusColor[video.status ?? ""] ?? "#555";
  const speakerLabel = (s: string) => s === "left" ? `<span style="color:#ff8a80">☯ 左</span>` : `<span style="color:#80d8ff">☆ 右</span>`;
  const scenesHtml = embeddedScenes.map((sc) => {
    const linesHtml = (sc.lines ?? []).map((l) =>
      `<div style="display:flex;gap:8px;margin:6px 0;align-items:flex-start">
        <div style="min-width:52px;font-size:0.75rem;padding:2px 6px;background:#1e1e2e;border-radius:4px;text-align:center">${speakerLabel(l.speaker)}</div>
        <div style="flex:1;line-height:1.6">${l.text.replace(/</g, "&lt;").replace(/>/g, "&gt;")}</div>
       </div>`
    ).join("");
    return `<div style="background:#1a1a2e;border-radius:8px;padding:12px 16px;margin-bottom:12px">
      <div style="font-size:0.75rem;color:#888;margin-bottom:8px">シーン ${sc.idx + 1}${sc.summary ? ` — ${sc.summary.replace(/</g, "&lt;").replace(/>/g, "&gt;")}` : ""}</div>
      ${linesHtml}
    </div>`;
  }).join("");
  const canonicalUrl = `https://yukkuri.etzhayyim.com/video/${rkey}`;
  return `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<title>${(video.title ?? "Yukkuri Video").replace(/</g, "&lt;")} — Yukkuri</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta property="og:title" content="${(video.title ?? "Yukkuri Video").replace(/"/g, "&quot;")}">
<meta property="og:description" content="${(video.topic ?? "").replace(/"/g, "&quot;").slice(0, 200)}">
<meta property="og:url" content="${canonicalUrl}">
<meta property="og:type" content="video.other">
<link rel="canonical" href="${canonicalUrl}">
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:#0f0f1a;color:#e0e0e0;min-height:100vh}
.container{max-width:720px;margin:0 auto;padding:24px 16px}
.header{margin-bottom:24px}
.badge{display:inline-block;padding:2px 10px;border-radius:12px;font-size:0.75rem;font-weight:600;background:${statusBg};color:#fff;margin-bottom:8px}
h1{font-size:1.4rem;font-weight:700;line-height:1.4;margin-bottom:6px}
.topic{color:#aaa;font-size:0.9rem;margin-bottom:12px}
.meta{display:flex;gap:12px;font-size:0.8rem;color:#888}
.scenes{margin-top:20px}
.scenes-title{font-size:0.85rem;text-transform:uppercase;letter-spacing:.08em;color:#666;margin-bottom:12px}
.footer{margin-top:32px;padding-top:16px;border-top:1px solid #1e1e2e;font-size:0.75rem;color:#555;text-align:center}
a{color:#90caf9;text-decoration:none}
</style>
</head>
<body>
<div class="container">
  <div class="header">
    <div class="badge">${(video.status ?? "unknown").toUpperCase()}</div>
    <h1>🎬 ${(video.title ?? "Yukkuri Video").replace(/</g, "&lt;").replace(/>/g, "&gt;")}</h1>
    ${video.topic ? `<div class="topic">${video.topic.replace(/</g, "&lt;").replace(/>/g, "&gt;").slice(0, 300)}</div>` : ""}
    <div class="meta">
      ${video.scene_count ? `<span>シーン ${video.scene_count}</span>` : ""}
      ${video.line_count ? `<span>セリフ ${video.line_count}</span>` : ""}
      ${video.duration_sec ? `<span>${Math.round(Number(video.duration_sec))}秒</span>` : ""}
      ${video.language ? `<span>${video.language.toUpperCase()}</span>` : ""}
    </div>
  </div>
  ${embeddedScenes.length > 0 ? `
  <div class="scenes">
    <div class="scenes-title">台本 / Script</div>
    ${scenesHtml}
  </div>` : `<p style="color:#555;font-size:0.9rem">台本はまだ生成されていません。</p>`}
  <div class="footer">
    <a href="https://yukkuri.etzhayyim.com">yukkuri.etzhayyim.com</a> &mdash; AI ゆっくり実況ジェネレーター
  </div>
</div>
</body>
</html>`;
}

async function handleVideoPage(env: Record<string, unknown>, rkey: string): Promise<Response> {
  try {
    const videoRow = await findVideoRow(env, rkey);
    let embeddedScenes: Array<{ idx: number; summary: string; durationSec: number; lines: Array<{ idx: number; speaker: string; text: string; emotion: string }> }> = [];
    if (videoRow?.scenes_json) {
      try { embeddedScenes = JSON.parse(videoRow.scenes_json); } catch { /* ignore */ }
    }
    const status = videoRow ? 200 : 404;
    return new Response(renderVideoHtml(rkey, videoRow ?? null, embeddedScenes), {
      headers: { "Content-Type": "text/html; charset=utf-8" },
      status,
    });
  } catch (err) {
    console.error("[yukkuri] handleVideoPage error", String((err as Error).message));
    return new Response(renderVideoHtml(rkey, null, []), { headers: { "Content-Type": "text/html; charset=utf-8" }, status: 500 });
  }
}

async function cmdHealth(): Promise<string> {
  return JSON.stringify({
    ok: true,
    app: "yukkuri",
    version: "0.2.0-phase1",
    murakumo: "configured",
    ongakuka: "binding:ONGAKUKA_SERVICE",
    renderPool: "todo:mac-pool",
    pipeline: {
      // Zeebe decommissioned (50-infra/vultr/zeebe removed, ADR-2607071500) —
      // the VKE cluster this broker ran on was permanently deleted 2026-06-24/25.
      // Dispatch is a plain HTTP POST to the dispatcher XRPC origin.
      bpmn: "yukkuriCompose.bpmn",
      tasks: ["yukkuri.scene.persist", "yukkuri.voice.synthesize",
              "yukkuri.image.generate", "yukkuri.video.assemble", "yukkuri.critic.review"],
      voice: "Phase0-stub (kokoro-ts todo)",
      image: "Phase0-stub (murakumo SDXL todo)",
      render: "Phase0-stub (kami-engine todo)",
    },
    ts: nowISO(),
  });
}

export default createWorkerExport((sdk) => {
  const env = (sdk as unknown as { env?: Record<string, unknown> }).env ?? {};

  // Web page: https://yukkuri.etzhayyim.com/video/:rkey — renders script/scenes as HTML.
  (sdk as unknown as { router?: { get: (path: string, handler: (c: { req: { param: (k: string) => string } }) => Promise<Response>) => void } }).router?.get(
    "/video/:rkey",
    async (c) => handleVideoPage(env, c.req.param("rkey")),
  );

  sdk.app
    .command(
      nsid("com.etzhayyim.apps.yukkuri.compose"),
      async (_ctx: unknown, body: Uint8Array) => cmdCompose(sdk, env, body),
      asAgentTool("Generate a yukkuri commentary video from a topic (Phase 0: script generation + pipeline enqueue)"),
      withCapabilityTags("write", "video", "dialogue", "generation"),
    )
    .command(
      nsid("com.etzhayyim.apps.yukkuri.regenerate"),
      async (_ctx: unknown, body: Uint8Array) => cmdRegenerate(sdk, body),
      asAgentTool("Regenerate a scene / line / asset within an existing yukkuri video project"),
      withCapabilityTags("write", "video", "regeneration"),
    )
    .command(
      nsid("com.etzhayyim.apps.yukkuri.render"),
      async (_ctx: unknown, body: Uint8Array) => cmdRender(sdk, body),
      asAgentTool("Trigger final mp4/webm render for an assembled yukkuri video project"),
      withCapabilityTags("write", "video", "render"),
    )
    .command(nsid("com.etzhayyim.apps.yukkuri.health"), async () => cmdHealth())
    .query(nsid("com.etzhayyim.apps.yukkuri.listVideos"), async (_ctx: unknown, body: Uint8Array) => cmdListVideos(sdk, env, body))
    .query(nsid("com.etzhayyim.apps.yukkuri.getVideo"), async (_ctx: unknown, body: Uint8Array) => cmdGetVideo(sdk, env, body));
});
