<script lang="ts">
  import { onMount } from 'svelte';
  import VideoGallery from './views/VideoGallery.svelte';
  import VideoDetail from './views/VideoDetail.svelte';
  import ComposeForm from './views/ComposeForm.svelte';

  type View = 'gallery' | 'detail' | 'compose';
  let view: View = $state('gallery');
  let context: Record<string, string> = $state({});

  function parsePath() {
    const { pathname } = window.location;
    // /at/{authority}/com.etzhayyim.apps.yukkuri.video/{rkey}
    const atMatch = pathname.match(/^\/at\/([^/]+)\/com\.etzhayyim\.apps\.yukkuri\.video\/([^/]+)$/);
    if (atMatch) {
      view = 'detail';
      context = { rkey: atMatch[2] };
      return;
    }
    if (pathname.startsWith('/video/')) {
      view = 'detail';
      context = { rkey: pathname.slice('/video/'.length) };
      return;
    }
    if (pathname === '/compose') {
      view = 'compose';
      context = {};
      return;
    }
    view = 'gallery';
    context = {};
  }

  function go(path: string) {
    window.history.pushState({}, '', path);
    parsePath();
  }

  onMount(() => {
    parsePath();
    window.addEventListener('popstate', parsePath);
    return () => window.removeEventListener('popstate', parsePath);
  });
</script>

<svelte:head>
  <title>Yukkuri — AI ゆっくり実況</title>
  <meta name="description" content="AI が台本・音声・映像を自動生成するゆっくり実況プラットフォーム" />
</svelte:head>

<div class="shell">
  <aside class="rail">
    <button class="brand" onclick={() => go('/')}>🎬 Yukkuri</button>
    <nav>
      <button class:active={view === 'gallery'} onclick={() => go('/')}>📹 動画一覧</button>
      <button class:active={view === 'compose'} onclick={() => go('/compose')}>✏️ 新規作成</button>
    </nav>
    <div class="spacer"></div>
    <div class="foot">did:web:yukkuri.etzhayyim.com</div>
  </aside>
  <main>
    {#if view === 'gallery'}
      <VideoGallery {go} />
    {:else if view === 'detail'}
      <VideoDetail rkey={context.rkey} {go} />
    {:else if view === 'compose'}
      <ComposeForm {go} />
    {/if}
  </main>
</div>

<style>
  :global(html, body, #app) {
    margin: 0;
    width: 100%;
    height: 100%;
    background: #0c0e14;
    color: #e6e8ee;
    font: 14px/1.4 -apple-system, 'Hiragino Sans', system-ui, sans-serif;
  }
  .shell {
    display: grid;
    grid-template-columns: 200px 1fr;
    height: 100vh;
  }
  .rail {
    background: #0f1118;
    border-right: 1px solid #1e2230;
    display: flex;
    flex-direction: column;
    padding: 12px 0;
  }
  .brand {
    font-size: 15px;
    font-weight: 700;
    background: none;
    border: 0;
    color: #e6e8ee;
    text-align: left;
    padding: 8px 16px;
    cursor: pointer;
    letter-spacing: 0.02em;
  }
  nav {
    display: flex;
    flex-direction: column;
    margin-top: 14px;
  }
  nav button {
    background: none;
    border: 0;
    color: #7a8090;
    text-align: left;
    padding: 10px 16px;
    cursor: pointer;
    font-size: 13px;
    border-left: 3px solid transparent;
  }
  nav button:hover { color: #e0e4f0; background: #1a1d26; }
  nav button.active { color: #e0e4f0; background: #151a28; border-left-color: #5ab0ff; }
  .spacer { flex: 1; }
  .foot {
    padding: 12px 16px;
    font-size: 10px;
    color: #333a4a;
    border-top: 1px solid #1e2230;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  main { overflow: hidden; display: flex; flex-direction: column; }
</style>
