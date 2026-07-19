const BASE = '/xrpc';

function unwrap<T>(raw: unknown): T {
  return (typeof raw === 'string' ? JSON.parse(raw) : raw) as T;
}

export async function listVideos(opts: { status?: string; offset?: number; limit?: number } = {}) {
  const params = new URLSearchParams();
  if (opts.status) params.set('status', opts.status);
  params.set('offset', String(opts.offset ?? 0));
  params.set('limit', String(opts.limit ?? 20));
  const r = await fetch(`${BASE}/com.etzhayyim.apps.yukkuri.listVideos?${params}`);
  if (!r.ok) throw new Error(`listVideos ${r.status}`);
  const data = unwrap<{ videos: VideoSummary[]; total: number; offset: number; limit: number }>(await r.json());
  return { ...data, videos: data.videos ?? [] };
}

export async function getVideo(videoUri: string) {
  const params = new URLSearchParams({ videoUri });
  const r = await fetch(`${BASE}/com.etzhayyim.apps.yukkuri.getVideo?${params}`);
  if (!r.ok) throw new Error(`getVideo ${r.status}`);
  return unwrap<VideoDetail>(await r.json());
}

export async function compose(input: {
  topic: string;
  title?: string;
  outline?: string;
}) {
  const r = await fetch(`${BASE}/com.etzhayyim.apps.yukkuri.compose`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!r.ok) throw new Error(`compose ${r.status}: ${await r.text()}`);
  return r.json() as Promise<{ videoUri: string; videoRkey: string; status: string }>;
}

export type VideoSummary = {
  videoUri: string;
  projectId: string;
  title: string;
  topic: string;
  status: string;
  sceneCount: number;
  lineCount: number;
  durationSec: number;
  blobKey: string;
  createdAt: string;
};

export type VideoDetail = {
  video: {
    videoUri: string;
    title: string;
    topic: string;
    status: string;
    language: string;
    sceneCount: number;
    lineCount: number;
    durationSec: number;
    fps: number;
    resolution: string;
    voiceLeft: string;
    voiceRight: string;
    blobKey: string;
    renderBlobKey: string;
    renderUrl: string;
    createdAt: string;
  };
  scenes: { index: number; summary: string; location?: string; action?: string; durationSec: number }[];
  lines: { sceneIndex: number; index: number; speaker: string; text: string; emotion: string }[];
  assets: { kind: string; blobKey: string }[];
  lastGeneration?: { stage: string; status: string; createdAt: string };
};

export function rkeyFromUri(uri: string): string {
  const m = uri.match(/\/([^/]+)$/);
  return m ? m[1] : uri;
}

export const STATUS_LABEL: Record<string, string> = {
  queued: 'キュー待ち',
  script: '台本生成済',
  assembled: '素材揃い',
  rendered: 'レンダー済',
  published: '公開済',
  rejected: '却下',
};

export const STATUS_COLOR: Record<string, string> = {
  queued: '#ff9800',
  script: '#2196f3',
  assembled: '#9c27b0',
  rendered: '#00bcd4',
  published: '#4caf50',
  rejected: '#f44336',
};
