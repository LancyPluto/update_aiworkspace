from __future__ import annotations

import json
from typing import Any


_TTS_PARAMETER_ALIASES = {
    "voice": ("voice", "voiceId", "voice_id"),
    "languageType": ("languageType", "language_type", "language"),
    "languageBoost": ("languageBoost", "language_boost"),
    "format": ("format", "audioFormat", "responseFormat"),
    "volume": ("volume", "vol"),
    "sampleRate": ("sampleRate", "sample_rate"),
    "ttsMode": ("ttsMode", "minimaxMode", "mode"),
    "minimaxGroupId": ("minimaxGroupId", "groupId", "GroupId"),
}


def merge_tts_params(
    model_config: dict[str, Any],
    *overrides: dict[str, Any] | None,
) -> dict[str, Any]:
    merged: dict[str, Any] = {}
    _merge_parameter_layer(
        merged,
        {
            key: model_config[key]
            for key in _TTS_PARAMETER_ALIASES["minimaxGroupId"]
            if key in model_config
        },
    )
    _merge_parameter_layer(
        merged,
        _json_object(
            model_config.get("executionOptionsJson")
            or model_config.get("execution_options_json")
        ),
    )
    for values in overrides:
        if isinstance(values, dict):
            _merge_parameter_layer(merged, values)
    return merged


def speech_billable_units(
    model_config: dict[str, Any],
    *,
    text: str,
    metadata: dict[str, Any] | None,
) -> int:
    if str(model_config.get("billingUnit") or "").strip().upper() != "PER_CHARACTER":
        return 1
    source = metadata if isinstance(metadata, dict) else {}
    for key in ("billableUnits", "billable_units", "usageCharacters", "usage_characters", "characters"):
        parsed = _positive_int(source.get(key))
        if parsed is not None:
            return parsed
    return len(text)


def _json_object(value: Any) -> dict[str, Any]:
    if isinstance(value, dict):
        return dict(value)
    if not isinstance(value, str) or not value.strip():
        return {}
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return {}
    return dict(parsed) if isinstance(parsed, dict) else {}


def _merge_parameter_layer(target: dict[str, Any], values: dict[str, Any]) -> None:
    normalized = dict(values)
    for canonical, aliases in _TTS_PARAMETER_ALIASES.items():
        selected = next(
            (
                values[key]
                for key in aliases
                if key in values and values[key] is not None and values[key] != ""
            ),
            None,
        )
        for key in aliases:
            normalized.pop(key, None)
        if selected is not None:
            normalized[canonical] = selected
    target.update(normalized)


def _positive_int(value: Any) -> int | None:
    if value is None or value == "":
        return None
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None
