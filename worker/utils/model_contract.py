import json
import re
from typing import Any


_FIELD_KEY_PATTERN = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,127}$")
_PATH_KEY_PATTERN = re.compile(r"[A-Za-z_][A-Za-z0-9_-]{0,127}")
_PATH_FILTER_VALUE_PATTERN = re.compile(r"[A-Za-z0-9_.:-]{1,128}")
_MAX_FIELD_MAPPINGS = 128
_MAX_PATH_LENGTH = 512
_MAX_PATH_TOKENS = 64


_MISSING = object()


class ModelContractParamsError(ValueError):
    pass


class ModelContractResponseError(ValueError):
    pass


def apply_request_mapping(
    params: Any,
    model_config: Any,
) -> dict[str, Any]:
    """Apply a v1 model field map without evaluating paths or expressions."""
    if params is None:
        source_params: dict[str, Any] = {}
    elif isinstance(params, dict):
        source_params = params
    else:
        raise ModelContractParamsError("task params must be a JSON object")

    if model_config is None:
        config: dict[str, Any] = {}
    elif isinstance(model_config, dict):
        config = model_config
    else:
        raise ModelContractParamsError("modelConfig must be a JSON object")

    raw_mapping = config.get("requestMappingJson")
    if raw_mapping is None:
        raw_mapping = config.get("request_mapping_json")
    if raw_mapping is None or (isinstance(raw_mapping, str) and not raw_mapping.strip()):
        return dict(source_params)

    mapping = _parse_mapping(raw_mapping, "requestMappingJson", ModelContractParamsError)
    field_map = mapping.get("fieldMap")
    if not isinstance(field_map, dict):
        raise ModelContractParamsError("requestMappingJson.fieldMap must be a JSON object")
    if len(field_map) > _MAX_FIELD_MAPPINGS:
        raise ModelContractParamsError(
            f"requestMappingJson.fieldMap cannot contain more than {_MAX_FIELD_MAPPINGS} entries"
        )

    normalized_entries: list[tuple[str, str]] = []
    target_keys: set[str] = set()
    for source_key, target_key in field_map.items():
        if not _is_valid_field_key(source_key):
            raise ModelContractParamsError(
                "requestMappingJson.fieldMap source keys must be simple field identifiers"
            )
        if not _is_valid_field_key(target_key):
            raise ModelContractParamsError(
                "requestMappingJson.fieldMap target keys must be simple field identifiers"
            )
        if target_key in target_keys:
            raise ModelContractParamsError(
                f"requestMappingJson.fieldMap contains duplicate target key: {target_key}"
            )
        target_keys.add(target_key)
        normalized_entries.append((source_key, target_key))

    merged = dict(source_params)
    for source_key, target_key in normalized_entries:
        if target_key not in source_params and source_key in source_params:
            merged[target_key] = source_params[source_key]
    return merged


def parse_response_mapping(model_config: Any, *, section: str | None = None) -> dict[str, Any]:
    """Parse the response mapping snapshot and optionally select a named mode."""
    if model_config is None:
        config: dict[str, Any] = {}
    elif isinstance(model_config, dict):
        config = model_config
    else:
        raise ModelContractResponseError("modelConfig must be a JSON object")

    raw_mapping = config.get("responseMappingJson")
    if raw_mapping is None:
        raw_mapping = config.get("response_mapping_json")
    if raw_mapping is None or (isinstance(raw_mapping, str) and not raw_mapping.strip()):
        return {}
    mapping = _parse_mapping(
        raw_mapping,
        "responseMappingJson",
        ModelContractResponseError,
        allow_empty=True,
    )
    if not mapping:
        return {}
    _validate_response_mapping_paths(mapping)
    if section is None:
        return mapping
    section_mapping = mapping.get(section)
    if section_mapping is None:
        return mapping
    if not isinstance(section_mapping, dict):
        raise ModelContractResponseError(f"responseMappingJson.{section} must be a JSON object")
    return {"version": mapping["version"], **section_mapping}


def response_mapping_has(mapping: Any, *mapping_keys: str) -> bool:
    return isinstance(mapping, dict) and any(key in mapping for key in mapping_keys)


def read_response_value(
    payload: Any,
    mapping: Any,
    *mapping_keys: str,
    fallback_paths: tuple[str, ...] = (),
) -> Any:
    """Read the first configured response path, or compatibility paths when absent."""
    paths = _response_paths(mapping, mapping_keys)
    if paths is None:
        paths = fallback_paths
    for path in paths:
        value = read_json_path(payload, path)
        if value is not _MISSING and value is not None:
            return value
    return None


def read_json_path(payload: Any, path: str) -> Any:
    """Read a constrained JSON path without expressions, wildcards, or evaluation."""
    tokens = _parse_json_path(path)
    current = payload
    for token_type, token_value in tokens:
        if token_type == "key":
            if not isinstance(current, dict) or token_value not in current:
                return _MISSING
            current = current[token_value]
            continue
        if token_type == "index":
            if not isinstance(current, list) or token_value >= len(current):
                return _MISSING
            current = current[token_value]
            continue
        filter_key, filter_value = token_value
        if not isinstance(current, list):
            return _MISSING
        current = next(
            (
                item
                for item in current
                if isinstance(item, dict)
                and filter_key in item
                and str(item.get(filter_key)) == filter_value
            ),
            _MISSING,
        )
        if current is _MISSING:
            return _MISSING
    return current


def _response_paths(mapping: Any, mapping_keys: tuple[str, ...]) -> tuple[str, ...] | None:
    if not isinstance(mapping, dict):
        return None
    for key in mapping_keys:
        if key not in mapping:
            continue
        raw_paths = mapping[key]
        if isinstance(raw_paths, str):
            values = [raw_paths]
        elif isinstance(raw_paths, list):
            values = raw_paths
        else:
            raise ModelContractResponseError(f"responseMappingJson.{key} must be a path or path array")
        if not values:
            raise ModelContractResponseError(f"responseMappingJson.{key} cannot be empty")
        paths: list[str] = []
        for value in values:
            if not isinstance(value, str) or not value.strip():
                raise ModelContractResponseError(
                    f"responseMappingJson.{key} must contain non-empty paths"
                )
            path = value.strip()
            _parse_json_path(path)
            paths.append(path)
        return tuple(paths)
    return None


def _validate_response_mapping_paths(mapping: dict[str, Any]) -> None:
    for key, value in mapping.items():
        if key == "version":
            continue
        if isinstance(value, dict):
            _validate_response_mapping_paths(value)
            continue
        if key.endswith("Path") or key.endswith("Paths"):
            _response_paths(mapping, (key,))


def _parse_json_path(path: Any) -> list[tuple[str, Any]]:
    if not isinstance(path, str) or not path or len(path) > _MAX_PATH_LENGTH:
        raise ModelContractResponseError("response mapping path is empty or too long")

    tokens: list[tuple[str, Any]] = []
    index = 0
    expect_key = True
    while index < len(path):
        if expect_key:
            match = _PATH_KEY_PATTERN.match(path, index)
            if match is None:
                raise ModelContractResponseError(f"invalid response mapping path: {path}")
            tokens.append(("key", match.group(0)))
            index = match.end()
            expect_key = False

        while index < len(path) and path[index] == "[":
            close_index = path.find("]", index + 1)
            if close_index < 0:
                raise ModelContractResponseError(f"invalid response mapping path: {path}")
            selector = path[index + 1 : close_index]
            if selector.isdigit():
                tokens.append(("index", int(selector)))
            else:
                filter_key, separator, filter_value = selector.partition("=")
                if (
                    not separator
                    or _PATH_KEY_PATTERN.fullmatch(filter_key) is None
                    or _PATH_FILTER_VALUE_PATTERN.fullmatch(filter_value) is None
                ):
                    raise ModelContractResponseError(f"invalid response mapping path: {path}")
                tokens.append(("filter", (filter_key, filter_value)))
            index = close_index + 1

        if len(tokens) > _MAX_PATH_TOKENS:
            raise ModelContractResponseError("response mapping path is too deep")
        if index == len(path):
            break
        if path[index] != ".":
            raise ModelContractResponseError(f"invalid response mapping path: {path}")
        index += 1
        expect_key = True

    if expect_key:
        raise ModelContractResponseError(f"invalid response mapping path: {path}")
    return tokens


def _parse_mapping(
    value: Any,
    field_name: str,
    error_type: type[ValueError],
    *,
    allow_empty: bool = False,
) -> dict[str, Any]:
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError as exc:
            raise error_type(f"{field_name} is not valid JSON") from exc
    elif isinstance(value, dict):
        parsed = value
    else:
        raise error_type(f"{field_name} must be a JSON object")

    if not isinstance(parsed, dict):
        raise error_type(f"{field_name} must be a JSON object")
    if allow_empty and not parsed:
        return {}
    if parsed.get("version") != "1":
        raise error_type(f"{field_name}.version must be '1'")
    return parsed


def _is_valid_field_key(value: Any) -> bool:
    return isinstance(value, str) and bool(_FIELD_KEY_PATTERN.fullmatch(value))
