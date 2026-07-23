import pytest

from utils.model_contract import ModelContractParamsError, apply_request_mapping


def test_request_mapping_maps_schema_fields_and_keeps_explicit_canonical_values() -> None:
    mapped = apply_request_mapping(
        {
            "promptInput": "mapped prompt",
            "ratioInput": "9:16",
            "prompt": "explicit prompt",
        },
        {
            "requestMappingJson": (
                '{"version":"1","fieldMap":'
                '{"promptInput":"prompt","ratioInput":"aspectRatio"}}'
            )
        },
    )

    assert mapped["prompt"] == "explicit prompt"
    assert mapped["aspectRatio"] == "9:16"
    assert mapped["promptInput"] == "mapped prompt"


@pytest.mark.parametrize(
    "mapping,error",
    [
        ("{not-json", "not valid JSON"),
        ('{"version":"1","fieldMap":{"prompt":"payload.prompt"}}', "simple field identifiers"),
        ('{"version":"2","fieldMap":{}}', "version must be '1'"),
    ],
)
def test_request_mapping_rejects_invalid_contracts(mapping: str, error: str) -> None:
    with pytest.raises(ModelContractParamsError, match=error):
        apply_request_mapping({"prompt": "test"}, {"requestMappingJson": mapping})
