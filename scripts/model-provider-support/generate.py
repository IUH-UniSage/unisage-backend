#!/usr/bin/env python3
"""Regenerate src/main/resources/model-provider-support.yml from LiteLLM's price map.

Every per-token-priced model the price sync stores (same filter rules as LiteLlmPriceParser) gets
an entry. Entries carrying `probed_at` (verified by calling the model through unisage-agent's
build_model()) are kept as they are; every other entry is derived:
  - non-chat modes (image, audio, rerank...) -> unsupported, with the reason
  - providers listed under `providers:` -> status "inferred" with those providers
  - anything else -> unsupported, with the reason when known
The `providers:` section is hand-maintained and copied through unchanged.

Usage (from unisage-backend/):
    python3 scripts/model-provider-support/generate.py [--source <price map json or URL>]
Requires PyYAML (pip install pyyaml) to read the existing file.
"""

from __future__ import annotations

import argparse
import json
import urllib.request
from decimal import Decimal
from pathlib import Path

import yaml

DEFAULT_SOURCE = (
    "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
)
TARGET = Path(__file__).resolve().parents[2] / "src/main/resources/model-provider-support.yml"

# Must match LiteLlmPriceParser.
PROVIDER_IDS = {"gemini": "google"}
MAX_PER_MILLION = Decimal("1000")
MAX_PROVIDER_LENGTH = 50
MAX_MODEL_NAME_LENGTH = 255

CHAT_MODES = {"chat", "embedding"}
MODE_NOTES = {
    "audio_speech": "Model chuyển văn bản thành giọng nói, không phải chat văn bản",
    "audio_transcription": "Model chuyển giọng nói thành văn bản, không phải chat văn bản",
    "completion": "Chỉ có API completions cũ, agent chưa hỗ trợ",
    "image_edit": "Model chỉnh sửa ảnh, không phải chat văn bản",
    "image_generation": "Model tạo ảnh, không phải chat văn bản",
    "moderation": "Model kiểm duyệt nội dung, không phải chat văn bản",
    "ocr": "Model OCR, không phải chat văn bản",
    "realtime": "Chỉ chạy qua Realtime API, agent chưa hỗ trợ",
    "rerank": "Model rerank, không phải chat văn bản",
    "responses": "Chỉ có Responses API, agent chưa hỗ trợ",
    "search": "API tìm kiếm, không phải chat văn bản",
    "video_generation": "Model tạo video, không phải chat văn bản",
}
OTHER_MODE_NOTE = "Không phải model chat hoặc embedding"


def provider_note(provider: str) -> str:
    if provider == "anthropic":
        return "Anthropic native chưa hỗ trợ (SDK cần httpx2)"
    if provider.startswith(("bedrock", "sagemaker")) or provider == "amazon_nova":
        return "Cần xác thực AWS, UniSage chưa hỗ trợ"
    if provider.startswith("vertex_ai"):
        return "Cần xác thực Google Cloud (Vertex AI), UniSage chưa hỗ trợ"
    if provider.startswith("azure"):
        return "Azure cần deployment và api-version riêng, UniSage chưa hỗ trợ"
    return "Nhà cung cấp chưa có trong danh sách tương thích của UniSage"


def per_million(value) -> Decimal | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    return Decimal(str(value)) * Decimal(1_000_000)


def in_range(value: Decimal | None) -> bool:
    return value is None or Decimal(0) <= value <= MAX_PER_MILLION


def priced_models(price_map: dict) -> dict[str, dict]:
    """(provider/model) -> LiteLLM spec, exactly the rows the price sync stores."""
    models: dict[str, dict] = {}
    from_prefixed: set[str] = set()
    for key, spec in price_map.items():
        if not isinstance(spec, dict):
            continue
        litellm_provider = str(spec.get("litellm_provider") or "").strip()
        if not litellm_provider:
            continue
        prefix = litellm_provider + "/"
        prefixed = key.startswith(prefix)
        name = key[len(prefix):] if prefixed else key
        provider = PROVIDER_IDS.get(litellm_provider, litellm_provider)
        if (not name.strip() or name == "sample_spec" or name.startswith("ft:")
                or len(name) > MAX_MODEL_NAME_LENGTH or len(provider) > MAX_PROVIDER_LENGTH):
            continue
        prices = [per_million(spec.get(field)) for field in
                  ("input_cost_per_token", "output_cost_per_token", "cache_read_input_token_cost")]
        if prices[0] is None or not all(in_range(p) for p in prices):
            continue
        model_key = f"{provider}/{name}".lower()
        if model_key in models and (model_key in from_prefixed or not prefixed):
            continue
        models[model_key] = spec
        if prefixed:
            from_prefixed.add(model_key)
    return models


def derive(model_key: str, spec: dict, defaults: dict[str, list[str]]) -> dict:
    mode = spec.get("mode")
    if mode not in CHAT_MODES:
        return {"providers": [], "status": "unsupported", "note": MODE_NOTES.get(mode, OTHER_MODE_NOTE)}
    provider = model_key.split("/", 1)[0]
    providers = defaults.get(provider, [])
    if providers:
        return {"providers": providers, "status": "inferred"}
    return {"providers": [], "status": "unsupported", "note": provider_note(provider)}


def flow(entry: dict) -> str:
    parts = [f"providers: [{', '.join(entry['providers'])}]", f"status: {entry['status']}"]
    if entry.get("note"):
        parts.append(f"note: {json.dumps(entry['note'], ensure_ascii=False)}")
    if entry.get("is_deprecated"):
        parts.append("is_deprecated: true")
    if entry.get("probed_at"):
        parts.append(f'probed_at: "{entry["probed_at"]}"')
    return "{" + ", ".join(parts) + "}"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--source", default=DEFAULT_SOURCE)
    args = parser.parse_args()

    if args.source.startswith("http"):
        with urllib.request.urlopen(args.source, timeout=60) as response:
            price_map = json.load(response)
    else:
        price_map = json.loads(Path(args.source).read_text())

    text = TARGET.read_text()
    current = yaml.safe_load(text)
    defaults = {k.lower(): [p.lower() for p in v] for k, v in current["providers"].items()}
    probed = {k.lower(): v for k, v in (current.get("models") or {}).items() if v.get("probed_at")}

    entries = {key: derive(key, spec, defaults) for key, spec in priced_models(price_map).items()}
    entries.update(probed)  # probed results win, and stay even if LiteLLM drops the model

    header = text[: text.index("\nmodels:")]
    lines = [header, "", "models:"]
    lines += [f"  {json.dumps(key, ensure_ascii=False)}: {flow(entries[key])}" for key in sorted(entries)]
    TARGET.write_text("\n".join(lines) + "\n")

    counts: dict[str, int] = {}
    for entry in entries.values():
        counts[entry["status"]] = counts.get(entry["status"], 0) + 1
    print(f"wrote {len(entries)} models to {TARGET.name}: {dict(sorted(counts.items()))}")


if __name__ == "__main__":
    main()
