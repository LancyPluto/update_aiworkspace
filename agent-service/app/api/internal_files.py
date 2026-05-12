from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from app.api.internal_runs import _verify_internal_request
from app.core.file_parser import FileParseError, chunk_text, parse_file_content

router = APIRouter()


class ParseFileRequest(BaseModel):
    filename: str
    contentType: str | None = None
    contentBase64: str


@router.post("/internal/v1/files/parse")
async def parse_file(request_body: ParseFileRequest, request: Request):
    await _verify_internal_request(request)
    try:
        text = parse_file_content(request_body.filename, request_body.contentType, request_body.contentBase64)
    except FileParseError as exception:
        raise HTTPException(status_code=400, detail=str(exception)) from exception
    return {"filename": request_body.filename, "text": text, "chunks": chunk_text(request_body.filename, text)}
