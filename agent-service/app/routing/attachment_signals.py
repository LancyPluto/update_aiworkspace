from __future__ import annotations

from dataclasses import dataclass

from app.core.schemas import AgentFileContext, RunContext
from app.core.user_attachment_priority import is_user_explicit_attachment


@dataclass(frozen=True, slots=True)
class AttachmentSignal:
    has_ready_media: bool
    ready_image_count: int
    ready_video_count: int
    ready_audio_count: int
    ready_document_count: int
    explicit_user_selection: bool
    synthetic_urls_only: bool
    total_attachments: int


def _is_ready_image(file: AgentFileContext) -> bool:
    if file.status != "READY":
        return False
    content_type = (file.contentType or "").lower()
    filename = (file.originalFilename or "").lower()
    return content_type.startswith("image/") or filename.endswith(
        (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")
    )


def _is_ready_video(file: AgentFileContext) -> bool:
    if file.status != "READY":
        return False
    content_type = (file.contentType or "").lower()
    filename = (file.originalFilename or "").lower()
    return content_type.startswith("video/") or filename.endswith((".mp4", ".mov", ".webm", ".mkv"))


def _is_ready_audio(file: AgentFileContext) -> bool:
    if file.status != "READY":
        return False
    content_type = (file.contentType or "").lower()
    filename = (file.originalFilename or "").lower()
    return content_type.startswith("audio/") or filename.endswith((".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac"))


def _is_ready_document(file: AgentFileContext) -> bool:
    if file.status != "READY":
        return False
    content_type = (file.contentType or "").lower()
    filename = (file.originalFilename or "").lower()
    if content_type.startswith(("image/", "video/", "audio/")):
        return False
    if filename.endswith((".png", ".jpg", ".jpeg", ".webp", ".gif", ".mp4", ".mov", ".mp3", ".wav")):
        return False
    return True


def build_attachment_signal(context: RunContext) -> AttachmentSignal:
    ready_images = 0
    ready_videos = 0
    ready_audios = 0
    ready_documents = 0
    explicit = False
    synthetic_only = True

    for file in context.agentFiles or []:
        if file.status != "READY":
            continue
        if _is_ready_image(file):
            ready_images += 1
        elif _is_ready_video(file):
            ready_videos += 1
        elif _is_ready_audio(file):
            ready_audios += 1
        elif _is_ready_document(file):
            ready_documents += 1
        if is_user_explicit_attachment(file):
            explicit = True
        if file.id is not None and file.id >= 0:
            synthetic_only = False

    has_ready_media = ready_images + ready_videos + ready_audios > 0
    return AttachmentSignal(
        has_ready_media=has_ready_media,
        ready_image_count=ready_images,
        ready_video_count=ready_videos,
        ready_audio_count=ready_audios,
        ready_document_count=ready_documents,
        explicit_user_selection=explicit,
        synthetic_urls_only=synthetic_only and bool(context.agentFiles),
        total_attachments=len(context.agentFiles or []),
    )


def attachment_signal_payload(signal: AttachmentSignal) -> dict:
    return {
        "hasReadyMedia": signal.has_ready_media,
        "readyImageCount": signal.ready_image_count,
        "readyVideoCount": signal.ready_video_count,
        "readyAudioCount": signal.ready_audio_count,
        "readyDocumentCount": signal.ready_document_count,
        "explicitUserSelection": signal.explicit_user_selection,
        "syntheticUrlsOnly": signal.synthetic_urls_only,
        "totalAttachments": signal.total_attachments,
    }
