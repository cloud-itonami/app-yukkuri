<script lang="ts">
  import { compose } from '../lib/api.js';

  let { go }: { go: (path: string) => void } = $props();

  let topic = $state('');
  let title = $state('');
  let outline = $state('');
  let submitting = $state(false);
  let result: { videoUri: string; videoRkey: string; status: string } | null = $state(null);
  let error = $state('');

  async function submit() {
    if (!topic.trim()) return;
    submitting = true;
    error = '';
    result = null;
    try {
      result = await compose({
        topic: topic.trim(),
        title: title.trim() || undefined,
        outline: outline.trim() || undefined,
      });
    } catch (e) {
      error = String((e as Error).message);
    } finally {
      submitting = false;
    }
  }
</script>

<div class="form-page">
  <div class="topbar">
    <button class="back" onclick={() => go('/')}>← 一覧へ</button>
  </div>

  <div class="form-card">
    <h2>🎬 新しいゆっくり動画を作成</h2>
    <p class="desc">トピックを入力するだけで、台本生成 → 音声合成 → 画像生成 → 動画レンダリングまで自動で行います。</p>

    {#if result}
      <div class="result-box">
        <div class="result-icon">✅</div>
        <p class="result-title">動画生成を開始しました</p>
        <p class="result-uri">{result.videoUri}</p>
        <div class="result-actions">
          <button class="primary-btn" onclick={() => go(`/video/${result!.videoRkey}`)}>
            動画ページを開く →
          </button>
          <button class="ghost-btn" onclick={() => { result = null; topic = ''; title = ''; outline = ''; }}>
            もう一つ作成
          </button>
        </div>
      </div>
    {:else}
      <form onsubmit={(e) => { e.preventDefault(); submit(); }}>
        <label>
          <span class="label-text">トピック <span class="required">*</span></span>
          <input
            type="text"
            bind:value={topic}
            placeholder="例: UAEがOPECから脱退　石油市場に何が起きるのか？"
            disabled={submitting}
            autocomplete="off"
          />
          <span class="hint">動画のメインテーマを入力してください</span>
        </label>

        <label>
          <span class="label-text">タイトル（任意）</span>
          <input
            type="text"
            bind:value={title}
            placeholder="例: ゆっくり解説: 石油市場の変化"
            disabled={submitting}
            autocomplete="off"
          />
        </label>

        <label>
          <span class="label-text">アウトライン（任意）</span>
          <textarea
            bind:value={outline}
            placeholder="説明したいポイントをメモ書き程度に。LLMが台本を作成します。"
            rows="4"
            disabled={submitting}
          ></textarea>
        </label>

        {#if error}
          <div class="error-box">{error}</div>
        {/if}

        <button type="submit" class="submit-btn" disabled={!topic.trim() || submitting}>
          {#if submitting}
            生成中…
          {:else}
            台本生成を開始する
          {/if}
        </button>
      </form>

      <div class="pipeline-info">
        <div class="pipeline-title">パイプライン</div>
        <div class="pipeline-steps">
          <span class="step">① 台本生成</span>
          <span class="arrow">→</span>
          <span class="step">② 音声合成</span>
          <span class="arrow">→</span>
          <span class="step">③ 画像生成</span>
          <span class="arrow">→</span>
          <span class="step">④ レンダリング</span>
          <span class="arrow">→</span>
          <span class="step">⑤ 審査・公開</span>
        </div>
      </div>
    {/if}
  </div>
</div>

<style>
  .form-page { padding: 24px; overflow-y: auto; height: 100%; box-sizing: border-box; }
  .topbar { margin-bottom: 20px; }
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
  .form-card {
    background: #14161f;
    border: 1px solid #22252d;
    border-radius: 12px;
    padding: 28px;
    max-width: 560px;
  }
  h2 { font-size: 1.15rem; font-weight: 700; margin: 0 0 8px; }
  .desc { color: #7a8090; font-size: 0.85rem; line-height: 1.6; margin: 0 0 24px; }
  form { display: flex; flex-direction: column; gap: 18px; }
  label { display: flex; flex-direction: column; gap: 5px; }
  .label-text { font-size: 0.82rem; font-weight: 600; color: #a0a4b0; }
  .required { color: #f44336; }
  .hint { font-size: 0.72rem; color: #555; }
  input, textarea {
    background: #0f1118;
    border: 1px solid #2a2d3a;
    border-radius: 6px;
    color: #e6e8ee;
    font-size: 0.88rem;
    padding: 9px 12px;
    font-family: inherit;
    resize: vertical;
  }
  input:focus, textarea:focus {
    outline: none;
    border-color: #5ab0ff;
  }
  input::placeholder, textarea::placeholder { color: #444; }
  input:disabled, textarea:disabled { opacity: 0.5; }
  .error-box {
    background: #2a1010;
    border: 1px solid #f44336;
    border-radius: 6px;
    padding: 10px 14px;
    color: #f44336;
    font-size: 0.82rem;
  }
  .submit-btn {
    background: #2a3a5c;
    border: 1px solid #5ab0ff;
    color: #90caf9;
    border-radius: 8px;
    padding: 10px 20px;
    font-size: 0.9rem;
    font-weight: 600;
    cursor: pointer;
    transition: background 0.15s;
    margin-top: 4px;
  }
  .submit-btn:hover:not(:disabled) { background: #344566; }
  .submit-btn:disabled { opacity: 0.4; cursor: not-allowed; }
  .pipeline-info {
    margin-top: 24px;
    padding-top: 20px;
    border-top: 1px solid #1e2230;
  }
  .pipeline-title { font-size: 0.72rem; text-transform: uppercase; letter-spacing: .08em; color: #444; margin-bottom: 10px; }
  .pipeline-steps { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
  .step { font-size: 0.75rem; color: #7a8090; background: #1a1d26; border-radius: 4px; padding: 3px 8px; }
  .arrow { color: #333; font-size: 0.75rem; }
  .result-box {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 24px;
    gap: 10px;
    text-align: center;
  }
  .result-icon { font-size: 2rem; }
  .result-title { font-size: 1rem; font-weight: 600; color: #4caf50; margin: 0; }
  .result-uri { font-size: 0.72rem; color: #555; word-break: break-all; margin: 0; }
  .result-actions { display: flex; gap: 10px; margin-top: 8px; }
  .primary-btn {
    background: #2a3a5c;
    border: 1px solid #5ab0ff;
    color: #90caf9;
    border-radius: 6px;
    padding: 8px 16px;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .primary-btn:hover { background: #344566; }
  .ghost-btn {
    background: none;
    border: 1px solid #2a2d3a;
    color: #7a8090;
    border-radius: 6px;
    padding: 8px 16px;
    font-size: 0.85rem;
    cursor: pointer;
  }
  .ghost-btn:hover { color: #e0e4f0; background: #1a1d26; }
</style>
