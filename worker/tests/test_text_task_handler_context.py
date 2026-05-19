from handlers.text_task_handler import TextTaskHandler


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
