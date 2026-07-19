<script lang="ts">
  import { onMount } from 'svelte';
  import { getVideo, STATUS_LABEL, STATUS_COLOR, type VideoDetail } from '../lib/api.js';

  let { rkey, go }: { rkey: string; go: (path: string) => void } = $props();

  let data: VideoDetail | null = $state(null);
  let loading = $state(true);
  let error = $state('');
  let activeScene: number | null = $state(null);

  const REPO = 'did:web:y5kk5r1x.etzhayyim.com';

  async function load() {
    loading = true;
    error = '';
    data = null;
    try {
      const uri = `at://${REPO}/com.etzhayyim.apps.yukkuri.video/${rkey}`;
      data = await getVideo(uri);
    } catch (e) {
      error = String((e as Error).message);
    } finally {
      loading = false;
    }
  }

  onMount(load);

  function linesForScene(idx: number) {
    return data?.lines.filter((l) => l.sceneIndex === idx) ?? [];
  }

  const EMOTION_EMOJI: Record<string, string> = {
    normal: '', happy: '😊', surprised: '😲', sad: '😢', angry: '😠',
  };
</script>

<div class="detail">
  <div class="topbar">
    <button class="back" onclick={() => go('/')}>← 一覧へ</button>
  </div>

  {#if loading}
    <div class="center">読み込み中…</div>
  {:else if error}
    <div class="center error">{error}</div>
  {:else if !data}
    <div class="center muted">動画が見つかりません: {rkey}</div>
  {:else}
    {@const v = data.video}
    <div class="header">
      <span class="badge" style="background:{STATUS_COLOR[v.status] ?? '#555'}">
        {STATUS_LABEL[v.status] ?? v.status}
      </span>
      <h1>{v.title || '(タイトルなし)'}</h1>
      {#if v.topic}
        <p class="topic">{v.topic}</p>
      {/if}
      <div class="meta-row">
        {#if v.sceneCount}<span>シーン {v.sceneCount}</span>{/if}
        {#if v.lineCount}<span>セリフ {v.lineCount}</span>{/if}
        {#if v.language}<span>{v.language.toUpperCase()}</span>{/if}
        {#if v.createdAt}<span>{v.createdAt.slice(0, 10)}</span>{/if}
      </div>
    </div>

    {#if v.renderUrl}
      <div class="video-player">
        <video controls src={v.renderUrl} preload="metadata">
          <track kind="captions" />
        </video>
        <div class="video-meta">
          <a href={v.renderUrl} target="_blank" rel="noopener noreferrer" class="dl-link">⬇ mp4 ダウンロード</a>
          {#if v.renderBlobKey}<span class="blob-key">{v.renderBlobKey}</span>{/if}
        </div>
      </div>
    {/if}

    {#if data.lines.length > 0}
      <div class="script-section">
        <div class="section-title">台本 / Script</div>
        {#each data.scenes as scene}
          {@const sceneLines = linesForScene(scene.index)}
          {#if sceneLines.length > 0}
            <div class="scene-block">
              <button
                class="scene-header"
                class:expanded={activeScene === scene.index}
                onclick={() => { activeScene = activeScene === scene.index ? null : scene.index; }}
              >
                <span class="scene-num">シーン {scene.index + 1}</span>
                {#if scene.location || scene.summary}
                  <span class="scene-loc">{scene.location ?? scene.summary ?? ''}</span>
                {/if}
                <span class="chevron">{activeScene === scene.index ? '▲' : '▼'}</span>
              </button>
              {#if activeScene === null || activeScene === scene.index}
                <div class="lines">
                  {#each sceneLines as line}
                    <div class="line" class:left={line.speaker === 'left'} class:right={line.speaker === 'right'}>
                      <div class="speaker-tag">
                        {#if line.speaker === 'left'}
                          <span class="l-tag">☯ ゆきり</span>
                        {:else}
                          <span class="r-tag">☆ まりり</span>
                        {/if}
                        {#if line.emotion && line.emotion !== 'normal'}
                          <span class="emotion">{EMOTION_EMOJI[line.emotion] ?? ''}</span>
                        {/if}
                      </div>
                      <div class="text">{line.text}</div>
                    </div>
                  {/each}
                </div>
              {/if}
            </div>
          {/if}
        {/each}

        {#if data.scenes.length === 0 && data.lines.length > 0}
          <div class="lines">
            {#each data.lines as line}
              <div class="line" class:left={line.speaker === 'left'} class:right={line.speaker === 'right'}>
                <div class="speaker-tag">
                  {#if line.speaker === 'left'}
                    <span class="l-tag">☯ ゆきり</span>
                  {:else}
                    <span class="r-tag">☆ まりり</span>
                  {/if}
                </div>
                <div class="text">{line.text}</div>
              </div>
            {/each}
          </div>
        {/if}
      </div>
    {:else}
      <div class="empty-script">
        <div class="empty-icon">📝</div>
        <p>台本はまだ生成されていません。</p>
        <p class="muted">ステータス: {STATUS_LABEL[v.status] ?? v.status}</p>
      </div>
    {/if}

    {#if data.assets.length > 0}
      <div class="assets-section">
        <div class="section-title">アセット</div>
        <div class="asset-chips">
          {#each data.assets as a}
            <span class="asset-chip">{a.kind}</span>
          {/each}
        </div>
      </div>
    {/if}

    {#if data.lastGeneration}
      {@const g = data.lastGeneration}
      <div class="gen-section">
        <span class="gen-stage">{g.stage}</span>
        <span class="gen-status">{g.status}</span>
      </div>
    {/if}
  {/if}
</div>

<style>
  .detail { padding: 24px; max-width: 740px; overflow-y: auto; height: 100%; box-sizing: border-box; }
  .topbar { margin-bottom: 16px; }
  .back {
    background: none;
    border: 1px solid #2a2d3a;
    color: #7a8090;
    padding: 4px 12px;
    border-radius: 6px;
    cursor: pointer;
    font-size: 0.82rem;
  }
  .back:hover { color: #e0e4f0; background: #1a1d26; }
  .center { display: flex; align-items: center; justify-content: center; height: 200px; color: #666; }
  .error { color: #f44336; }
  .muted { color: #555; }
  .header { margin-bottom: 24px; }
  .badge {
    display: inline-block;
    padding: 2px 10px;
    border-radius: 10px;
    font-size: 0.72rem;
    font-weight: 600;
    color: #fff;
    margin-bottom: 10px;
  }
  h1 { font-size: 1.25rem; font-weight: 700; color: #e6e8ee; margin: 0 0 8px; line-height: 1.4; }
  .topic { color: #7a8090; font-size: 0.88rem; line-height: 1.6; margin: 0 0 12px; }
  .meta-row { display: flex; gap: 12px; font-size: 0.75rem; color: #555; flex-wrap: wrap; }
  .section-title {
    font-size: 0.72rem;
    text-transform: uppercase;
    letter-spacing: .08em;
    color: #555;
    margin-bottom: 12px;
  }
  .script-section { margin-bottom: 28px; }
  .scene-block { margin-bottom: 10px; }
  .scene-header {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    background: #1a1d26;
    border: 1px solid #22252d;
    border-radius: 7px;
    padding: 8px 12px;
    cursor: pointer;
    font-size: 0.8rem;
    color: #a0a4b0;
    text-align: left;
  }
  .scene-header:hover { background: #1e2230; color: #e0e4f0; }
  .scene-num { font-weight: 600; color: #e0e4f0; }
  .scene-loc { flex: 1; color: #7a8090; font-size: 0.75rem; }
  .chevron { margin-left: auto; font-size: 0.7rem; }
  .lines { padding: 8px 0 4px 4px; display: flex; flex-direction: column; gap: 8px; }
  .line {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    padding: 8px 10px;
    border-radius: 6px;
    background: #13151e;
    border-left: 3px solid transparent;
  }
  .line.left { border-left-color: #ff6b6b; }
  .line.right { border-left-color: #5ab0ff; }
  .speaker-tag { display: flex; flex-direction: column; align-items: center; gap: 2px; min-width: 54px; }
  .l-tag { font-size: 0.7rem; color: #ff8a80; font-weight: 600; white-space: nowrap; }
  .r-tag { font-size: 0.7rem; color: #80c8ff; font-weight: 600; white-space: nowrap; }
  .emotion { font-size: 0.85rem; }
  .text { font-size: 0.88rem; line-height: 1.7; color: #d0d4e0; flex: 1; }
  .empty-script {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 48px 0;
    gap: 8px;
    color: #555;
  }
  .empty-icon { font-size: 2.5rem; }
  .assets-section { margin-bottom: 16px; }
  .asset-chips { display: flex; gap: 6px; flex-wrap: wrap; }
  .asset-chip {
    background: #1a1d26;
    border: 1px solid #2a2d3a;
    border-radius: 10px;
    padding: 2px 10px;
    font-size: 0.72rem;
    color: #7a8090;
  }
  .gen-section {
    display: flex;
    gap: 8px;
    font-size: 0.75rem;
    color: #555;
    margin-top: 8px;
  }
  .gen-stage { color: #7a8090; }
  .gen-status { color: #5ab0ff; }
  .video-player { margin-bottom: 24px; }
  .video-player video { width: 100%; border-radius: 8px; background: #000; display: block; max-height: 400px; }
  .video-meta { display: flex; align-items: center; gap: 12px; margin-top: 8px; flex-wrap: wrap; }
  .dl-link { font-size: 0.78rem; color: #80c8ff; text-decoration: none; }
  .dl-link:hover { text-decoration: underline; }
  .blob-key { font-size: 0.7rem; color: #444; font-family: monospace; }
</style>
