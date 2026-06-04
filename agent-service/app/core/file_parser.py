import base64
import csv
import io
import json
import zipfile
from html import unescape
from xml.etree import ElementTree


class FileParseError(ValueError):
    pass


def parse_file_content(filename: str, content_type: str | None, content_base64: str) -> str:
    try:
        content = base64.b64decode(content_base64, validate=True)
    except Exception as exception:
        raise FileParseError("contentBase64 is not valid base64") from exception

    lowered_name = (filename or "").lower()
    lowered_type = (content_type or "").lower()
    if _is_text_file(lowered_name, lowered_type):
        return _decode_text(content)
    if lowered_name.endswith(".docx") or "wordprocessingml.document" in lowered_type:
        return _parse_docx(content)
    if lowered_name.endswith(".csv") or "csv" in lowered_type:
        return _parse_csv(content)
    if lowered_name.endswith(".json") or "json" in lowered_type:
        return _parse_json(content)
    if lowered_name.endswith(".pdf") or lowered_type == "application/pdf":
        return _parse_pdf(content)
    if _is_image_file(lowered_name, lowered_type):
        label = filename.strip() if filename and filename.strip() else "image"
        return (
            f"[用户已上传图片：{label}]\n"
            "该图片已随当前消息提交，可作为图生视频/图像工具的首帧或参考图输入；"
            "请勿再要求用户重新上传或提供图片链接。"
        )
    raise FileParseError("unsupported file type")


def chunk_text(filename: str, text: str, chunk_size: int = 1200, chunk_overlap: int = 120) -> list[dict]:
    normalized = (text or "").strip()
    if not normalized:
        return []
    try:
        from langchain_text_splitters import RecursiveCharacterTextSplitter
    except ImportError:
        try:
            from langchain.text_splitter import RecursiveCharacterTextSplitter
        except ImportError:
            return _fallback_chunks(filename, normalized, chunk_size, chunk_overlap)
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
        separators=["\n\n", "\n", "。", ".", " ", ""],
    )
    documents = splitter.create_documents([normalized], metadatas=[{"source": filename}])
    return [
        {
            "chunkIndex": index,
            "content": document.page_content.strip(),
            "metadata": {**document.metadata, "source": filename, "chunkIndex": index},
        }
        for index, document in enumerate(documents)
        if document.page_content.strip()
    ]


def _is_image_file(filename: str, content_type: str) -> bool:
    image_suffixes = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic", ".heif", ".avif")
    return filename.endswith(image_suffixes) or content_type.startswith("image/")


def _is_text_file(filename: str, content_type: str) -> bool:
    text_suffixes = (".txt", ".md", ".markdown", ".log")
    return filename.endswith(text_suffixes) or content_type.startswith("text/")


def _decode_text(content: bytes) -> str:
    for encoding in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return content.decode(encoding).strip()
        except UnicodeDecodeError:
            continue
    raise FileParseError("could not decode text file")


def _parse_csv(content: bytes) -> str:
    text = _decode_text(content)
    rows = csv.reader(io.StringIO(text))
    return "\n".join(" | ".join(cell.strip() for cell in row) for row in rows).strip()


def _parse_json(content: bytes) -> str:
    text = _decode_text(content)
    return json.dumps(json.loads(text), ensure_ascii=False, indent=2)


def _parse_docx(content: bytes) -> str:
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            xml = archive.read("word/document.xml")
    except Exception as exception:
        raise FileParseError("could not read docx document") from exception
    root = ElementTree.fromstring(xml)
    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    paragraphs: list[str] = []
    for paragraph in root.iter(namespace + "p"):
        parts = [node.text or "" for node in paragraph.iter(namespace + "t")]
        line = unescape("".join(parts)).strip()
        if line:
            paragraphs.append(line)
    return "\n".join(paragraphs).strip()


def _parse_pdf(content: bytes) -> str:
    try:
        import pymupdf
    except ImportError:
        raise FileParseError("pymupdf is required to parse PDF files")
    try:
        doc = pymupdf.open(stream=content, filetype="pdf")
    except Exception as exception:
        raise FileParseError(f"could not read pdf document: {exception}") from exception
    parts: list[str] = []
    for page in doc:
        text = page.get_text().strip()
        if text:
            parts.append(text)
    doc.close()
    return "\n\n".join(parts).strip()


def _fallback_chunks(filename: str, text: str, chunk_size: int, chunk_overlap: int) -> list[dict]:
    paragraphs = [part.strip() for part in text.split("\n\n") if part.strip()]
    if paragraphs and all(len(part) <= chunk_size for part in paragraphs):
        chunks = paragraphs
    else:
        chunks = []
        start = 0
        while start < len(text):
            end = min(len(text), start + chunk_size)
            chunks.append(text[start:end].strip())
            if end == len(text):
                break
            start = max(0, end - chunk_overlap)
    return [
        {
            "chunkIndex": index,
            "content": chunk,
            "metadata": {"source": filename, "chunkIndex": index},
        }
        for index, chunk in enumerate(chunks)
        if chunk
    ]
