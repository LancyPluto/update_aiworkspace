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
        /** 用户在本条消息中优先选择的 Agent 工具 code，仅影响本次 run 的路由偏置。 */
        String preferredToolCode,
        /** 随消息一并提交的会话附件 ID；为空则绑定当前会话全部待发送附件。 */
        List<Long> fileIds,
        /** 已有素材 URL 附件，例如素材库中的 /generated/... 结果。 */
        List<Map<String, Object>> urlAttachments
) {
}
