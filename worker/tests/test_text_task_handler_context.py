from handlers.text_task_handler import TextTaskHandler, _select_model_request_params


class DummyModelClient:
    default_model_name = "dummy-model"


def test_live_stream_script_uses_long_output_budget():
    handler = TextTaskHandler(backend_client=object(), model_client=DummyModelClient())

    context = handler._normalize_execution_context(
        {
            "toolCode": "live_stream_script_generator",
            "params": {},
            "modelConfig": {
                "provider": "mock",
                "baseUrl": "http://model.local",
                "apiKey": "key",
            },
        }
    )

    assert context["modelMaxTokens"] == 4096
    assert context["modelTimeoutSeconds"] == 120


def test_model_config_can_override_output_budget():
    handler = TextTaskHandler(backend_client=object(), model_client=DummyModelClient())

    context = handler._normalize_execution_context(
        {
            "toolCode": "live_stream_script_generator",
            "params": {},
            "modelConfig": {
                "provider": "mock",
                "baseUrl": "http://model.local",
                "apiKey": "key",
                "maxTokens": 6000,
                "timeoutSeconds": 180,
            },
        }
    )

    assert context["modelMaxTokens"] == 6000
    assert context["modelTimeoutSeconds"] == 180


def test_social_media_comment_insights_uses_report_budget():
    handler = TextTaskHandler(backend_client=object(), model_client=DummyModelClient())

    context = handler._normalize_execution_context(
        {
            "toolCode": "social_media_comment_insights_agent",
            "params": {},
            "modelConfig": {
                "provider": "mock",
                "baseUrl": "http://model.local",
                "apiKey": "key",
            },
        }
    )

    assert context["modelMaxTokens"] == 4096
    assert context["modelTimeoutSeconds"] == 180


def test_text_model_params_only_include_schema_fields_and_mapped_targets():
    selected = _select_model_request_params(
        {
            "prompt": "hello",
            "temperature": 0.4,
            "topP": 0.8,
            "top_p": 0.8,
            "responseFormat": "MARKDOWN",
        },
        {
            "requestSchemaJson": (
                '{"version":"1","fields":['
                '{"key":"prompt","type":"string"},'
                '{"key":"temperature","type":"number"},'
                '{"key":"topP","type":"number"}]}'
            ),
            "requestMappingJson": '{"version":"1","fieldMap":{"topP":"top_p"}}',
        },
    )

    assert selected == {
        "prompt": "hello",
        "temperature": 0.4,
        "topP": 0.8,
        "top_p": 0.8,
    }
