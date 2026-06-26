package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

public record CreateAgentMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容过长")
        String content,
        String clientRequestId,
        Long modelConfigId,
        String intelligenceLevel,
        /** 用户在本条消息中优先选择的 Agent 工具 code，仅影响本次 run 的路由偏置。 */
        String preferredToolCode,
        /** 随消息一并提交的会话附件 ID；为空则绑定当前会话全部待发送附件。 */
        List<Long> fileIds,
        /** 已有素材 URL 附件，例如素材库中的 /generated/... 结果。 */
        List<Map<String, Object>> urlAttachments,
        /** 结构化 @ 引用（展示 token + refLabel + url），供 agent-service 映射附件。 */
        List<Map<String, Object>> referenceMentions,
        /** 暂存区全局素材 ID，包含上传文件 ID 和 URL 素材稳定 key。 */
        List<Object> globalFileIds,
        /** 按输入框 DOM 顺序序列化的 OpenAI-style 多模态片段。 */
        List<Map<String, Object>> contentParts,
        /** 将 inline chip 替换成 {asset_key} 变量后的站位提示词。 */
        String positionalPrompt,
        /** 当前分支叶子消息 ID；新消息会挂载到该节点下。为空时使用会话 active leaf。 */
        Long parentMessageId
) {
}
