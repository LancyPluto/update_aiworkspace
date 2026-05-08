from typing import Any, Dict, List, Optional, Union

from pydantic import BaseModel, ConfigDict, Field


class TaskQueueMessage(BaseModel):
    """与开发包约定一致的队列消息。"""

    model_config = ConfigDict(populate_by_name=True)

    task_id: int = Field(alias="taskId")
    task_no: str = Field(alias="taskNo")
    tool_code: str = Field(alias="toolCode")
    trace_id: Optional[str] = Field(default=None, alias="traceId")
    created_at: Optional[str] = Field(default=None, alias="createdAt")


class ExecutionContext(BaseModel):
    """GET execution-context 解析体（与后端字段对齐，可扩展）。"""

    model_config = ConfigDict(populate_by_name=True)

    task_id: int = Field(alias="taskId")
    task_no: str = Field(alias="taskNo")
    tool_code: str = Field(alias="toolCode")
    status: str
    params: Dict[str, Any] = Field(default_factory=dict)
    system_prompt: str = Field(alias="systemPrompt")
    user_prompt_template: str = Field(alias="userPromptTemplate")
    output_format: str = Field(alias="outputFormat")
    model_provider_code: str = Field(alias="modelProviderCode")
    model_name: str = Field(alias="modelName")


class SuccessPayload(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    resource_type: str = Field(alias="resourceType")
    content_text: str = Field(alias="contentText")
    content_json: Optional[Union[Dict[str, Any], List[Any]]] = Field(
        default=None, alias="contentJson"
    )
    model_provider_code: str = Field(alias="modelProviderCode")
    model_name: str = Field(alias="modelName")


class FailedPayload(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    error_code: str = Field(alias="errorCode")
    error_message: str = Field(alias="errorMessage")
