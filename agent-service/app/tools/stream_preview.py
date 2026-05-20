STREAM_PREVIEW_PREFIX = "STREAM_PREVIEW:"


def extract_stream_preview(progress_message: str | None) -> str:
    if not progress_message:
        return ""
    message = progress_message.strip()
    if not message.startswith(STREAM_PREVIEW_PREFIX):
        return ""
    return message[len(STREAM_PREVIEW_PREFIX) :].strip()
