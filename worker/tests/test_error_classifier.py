from handlers.error_classifier import classify_model_error


def test_image_generation_group_permission_is_capability_disabled():
    message = (
        'openai images request failed: status=403, body={"error":'
        '{"message":"Image generation is not enabled for this group","type":"permission_error"}}'
    )

    assert classify_model_error(message) == "MODEL_CAPABILITY_DISABLED"
