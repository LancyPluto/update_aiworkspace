package com.aiminilab.aitoolmarket.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateAgentMessageRequest(
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容过长")
        String content,
        String clientRequestId,
        Long modelConfigId,
        /** 随消息一并提交的会话附件 ID；为空则绑定当前会话全部待发送附件。 */
        List<Long> fileIds
) {
}
