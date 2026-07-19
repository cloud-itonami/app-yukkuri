"""API-format ComfyUI workflow builders for yukkuri.etzhayyim.com scene generation.

Each builder returns a {node_id: {class_type, inputs}} dict that comfy_runner
POSTs to ComfyUI's /prompt endpoint. Three builders:

  background_workflow(...)    one establishing background per scene (1920x1080)
  character_workflow(...)     one character立ち絵 sheet (1024x1536, transparent BG)
  scene_composite_workflow(...)  composite: background + L立ち絵 + R立ち絵 (1920x1080)

All builders accept overrides for checkpoint, sampler, seed, steps, cfg so
the operator can sweep params without editing JSON.

Defaults follow the animeka v3 cinematic pack (illustrious / animagine XL).
"""

from __future__ import annotations

import os as _os
import secrets
from typing import Any

DEFAULT_CKPT = _os.environ.get(
    "COMFY_DEFAULT_CKPT",
    "waiIllustriousSDXL_v160.safetensors",
)
DEFAULT_SAMPLER = "dpmpp_3m_sde_gpu"
DEFAULT_SCHEDULER = "karras"

NEG_DEFAULT = (
    "(low quality:1.4), (worst quality:1.4), blurry, jpeg artifacts, "
    "watermark, signature, real person, logo, deformed, extra limbs, "
    "bad anatomy, color photograph, out of frame, mutated, cropped, "
    "nsfw, explicit, real artist name"
)

# Yukkuri voice-actor sub-DID display names. Independent of東方 IP — these are
# etzhayyim original characters. Names mirror CLAUDE.md "ゆきり / まりり" convention.
LEFT_CHARACTER_DEFAULT = "ゆきり"   # Reimu-like, calm
RIGHT_CHARACTER_DEFAULT = "まりり"  # Marisa-like, energetic

LEFT_STYLE = (
    "young female, long black hair, red ribbon, white shirt, red skirt, "
    "calm expression, friendly smile, three-quarter view, anime style"
)
RIGHT_STYLE = (
    "young female, blonde hair, witch hat, black-and-white dress, "
    "energetic expression, open mouth, three-quarter view, anime style"
)


def _seed() -> int:
    return secrets.randbelow(2**32)


def background_workflow(
    *,
    location: str,
    action: str,
    style_hint: str = "soft colors, daylight, cinematic establishing shot",
    seed: int = 0,
    steps: int = 28,
    cfg: float = 7.0,
    sampler: str = DEFAULT_SAMPLER,
    scheduler: str = DEFAULT_SCHEDULER,
    width: int = 1920,
    height: int = 1080,
    ckpt: str = DEFAULT_CKPT,
    negative: str = NEG_DEFAULT,
    filename_hint: str = "yukkuri-bg",
) -> dict[str, Any]:
    """Single-pass background generator. 1920x1080 widescreen, no characters."""
    prompt = (
        f"anime style background only, no characters, no people, "
        f"{location}, {action}, {style_hint}, "
        f"2D illustration, detailed background, masterpiece, best quality"
    )
    real_seed = int(seed) or _seed()
    safe = "".join(c if c.isalnum() or c in "-_" else "-" for c in filename_hint)[:40]
    return {
        "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": ckpt}},
        "2": {"class_type": "EmptyLatentImage", "inputs": {
            "width": int(width), "height": int(height), "batch_size": 1}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["1", 1]}},
        "4": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["1", 1]}},
        "5": {"class_type": "KSampler", "inputs": {
            "seed": real_seed, "steps": int(steps), "cfg": float(cfg),
            "sampler_name": sampler, "scheduler": scheduler, "denoise": 1.0,
            "model": ["1", 0], "positive": ["3", 0], "negative": ["4", 0],
            "latent_image": ["2", 0]}},
        "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage", "inputs": {
            "filename_prefix": safe, "images": ["6", 0]}},
    }


def character_workflow(
    *,
    name: str,
    side: str,
    description: str | None = None,
    emotion: str = "neutral",
    seed: int = 0,
    steps: int = 32,
    cfg: float = 7.5,
    sampler: str = DEFAULT_SAMPLER,
    scheduler: str = DEFAULT_SCHEDULER,
    width: int = 1024,
    height: int = 1536,
    ckpt: str = DEFAULT_CKPT,
    negative: str = NEG_DEFAULT,
) -> dict[str, Any]:
    """Single character立ち絵, transparent background ready for compositing.

    side ∈ {"left","right"} selects ゆきり (calm) / まりり (energetic) default
    style. `description` (optional) overrides the default look. `emotion`
    drives expression prompt (normal/happy/surprised/sad/angry).
    """
    side_lc = (side or "").lower()
    if side_lc not in ("left", "right"):
        side_lc = "left"
    base_style = LEFT_STYLE if side_lc == "left" else RIGHT_STYLE
    look = description.strip() if description else base_style
    emotion_prompt = {
        "normal": "calm neutral expression",
        "happy": "happy smile, sparkling eyes",
        "surprised": "wide open eyes, open mouth, surprised expression",
        "sad": "sad expression, downcast eyes",
        "angry": "angry expression, furrowed brows",
    }.get(emotion, "calm neutral expression")
    prompt = (
        f"single character, full body, standing pose, transparent background, "
        f"{look}, {emotion_prompt}, "
        f"anime style, cleanly lined, masterpiece, best quality, vibrant colors"
    )
    real_seed = int(seed) or _seed()
    safe_name = "".join(c if c.isalnum() or c in "-_" else "-" for c in name)[:40] or "char"
    safe_side = side_lc
    return {
        "1": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": ckpt}},
        "2": {"class_type": "EmptyLatentImage", "inputs": {
            "width": int(width), "height": int(height), "batch_size": 1}},
        "3": {"class_type": "CLIPTextEncode", "inputs": {"text": prompt, "clip": ["1", 1]}},
        "4": {"class_type": "CLIPTextEncode", "inputs": {"text": negative, "clip": ["1", 1]}},
        "5": {"class_type": "KSampler", "inputs": {
            "seed": real_seed, "steps": int(steps), "cfg": float(cfg),
            "sampler_name": sampler, "scheduler": scheduler, "denoise": 1.0,
            "model": ["1", 0], "positive": ["3", 0], "negative": ["4", 0],
            "latent_image": ["2", 0]}},
        "6": {"class_type": "VAEDecode", "inputs": {"samples": ["5", 0], "vae": ["1", 2]}},
        "7": {"class_type": "SaveImage", "inputs": {
            "filename_prefix": f"yukkuri-char-{safe_side}-{safe_name}",
            "images": ["6", 0]}},
    }


def scene_composite_workflow(
    *,
    background_filename: str,
    left_filename: str,
    right_filename: str,
    width: int = 1920,
    height: int = 1080,
    filename_hint: str = "yukkuri-scene",
) -> dict[str, Any]:
    """Composite a background + L立ち絵 + R立ち絵 into one widescreen frame.

    Inputs are filenames already living in ComfyUI's input/ dir (uploaded via
    /upload/image or referenced from background/character runs that saved
    under output/). The compositor scales L and R to ~70% of frame height,
    pins L to the left third and R to the right third, and saves a
    1920x1080 PNG.
    """
    safe = "".join(c if c.isalnum() or c in "-_" else "-" for c in filename_hint)[:40]
    return {
        "1": {"class_type": "LoadImage", "inputs": {"image": background_filename}},
        "2": {"class_type": "LoadImage", "inputs": {"image": left_filename}},
        "3": {"class_type": "LoadImage", "inputs": {"image": right_filename}},
        # Scale L立ち絵 to ~70% frame height
        "4": {"class_type": "ImageScale", "inputs": {
            "image": ["2", 0],
            "upscale_method": "lanczos",
            "width": int(width // 3),
            "height": int(height * 0.7),
            "crop": "disabled"}},
        # Scale R立ち絵 to ~70% frame height
        "5": {"class_type": "ImageScale", "inputs": {
            "image": ["3", 0],
            "upscale_method": "lanczos",
            "width": int(width // 3),
            "height": int(height * 0.7),
            "crop": "disabled"}},
        # Ensure background is exactly widescreen
        "6": {"class_type": "ImageScale", "inputs": {
            "image": ["1", 0],
            "upscale_method": "lanczos",
            "width": int(width),
            "height": int(height),
            "crop": "center"}},
        # Composite L立ち絵 onto bg at x=80 y=bottom-anchored
        "7": {"class_type": "ImageCompositeMasked", "inputs": {
            "destination": ["6", 0],
            "source": ["4", 0],
            "x": 80,
            "y": int(height - height * 0.7) - 20,
            "resize_source": False}},
        # Composite R立ち絵 onto result at x=width-1/3-80 y=bottom-anchored
        "8": {"class_type": "ImageCompositeMasked", "inputs": {
            "destination": ["7", 0],
            "source": ["5", 0],
            "x": int(width - (width // 3) - 80),
            "y": int(height - height * 0.7) - 20,
            "resize_source": False}},
        "9": {"class_type": "SaveImage", "inputs": {
            "filename_prefix": safe, "images": ["8", 0]}},
    }
