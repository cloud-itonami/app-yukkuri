"""yukkuri.etzhayyim.com LangGraph Server actor.

AI ゆっくり動画生成パイプライン。1 topic → 台本 → TTS → 画像 → BGM → render → review → publish。

Mirrors lg-animeka OSS FastAPI pattern: no LangSmith license required,
RW-compat checkpointer, fire-and-forget BPMN audit shim.

Graphs:
  health          — RW probe + liveness
  list_videos     — vertex_yukkuri_video 一覧
  get_video       — 動画詳細 (scenes + lines + assets)
  compose         — topic → video enqueue (status: queued)
  generate_script — scriptwriter: LLM → L/R 掛け合い台本 + scene 分割
  synthesize_voice — voiceLeft/voiceRight: kokoro-ts TTS (L + R 並列)
  generate_visual  — illustrator: 背景 + 挿絵 (murakumo image)
  generate_bgm     — composer: ongakuka.compose invoke
  render_video     — renderer: timeline JSON → mac render pool → mp4
  review_video     — critic: IP / 表現 / deepfake QA
"""

__version__ = "0.1.0"
