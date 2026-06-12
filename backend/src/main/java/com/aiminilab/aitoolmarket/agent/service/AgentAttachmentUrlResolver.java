package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes agent session file attachments to {@code /generated/} so workers can fetch them without user JWT.
 */
@Service
public class AgentAttachmentUrlResolver {

    private static final Pattern AGENT_FILE_PATH = Pattern.compile(
            "/api/v1/agent/sessions/(\\d+)/files/(\\d+)/content(?:\\?.*)?$",
            Pattern.CASE_INSENSITIVE
    );

    private final AgentFileMapper agentFileMapper;
    private final AgentFileService agentFileService;
    private final AssetStorageService assetStorageService;

    public AgentAttachmentUrlResolver(AgentFileMapper agentFileMapper,
                                      AgentFileService agentFileService,
                                      AssetStorageService assetStorageService) {
        this.agentFileMapper = agentFileMapper;
        this.agentFileService = agentFileService;
        this.assetStorageService = assetStorageService;
    }

    public String resolveForWorker(Long userId, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("/generated/") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return assetStorageService.workerAccessibleUrl(trimmed);
        }
        Optional<AgentFileRef> ref = parseAgentFileRef(trimmed);
        if (ref.isEmpty()) {
            return trimmed;
        }
        AgentFileRef parsed = ref.get();
        AgentFile file = agentFileMapper.selectById(parsed.fileId());
        if (file == null) {
            throw new BusinessException(
                    ErrorCode.PARAM_ERROR,
                    "attachment not found: sessionId=" + parsed.sessionId() + " fileId=" + parsed.fileId()
            );
        }
        if (userId != null && !userId.equals(file.getUserId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "attachment owner mismatch");
        }
        Long ownerId = file.getUserId();
        return publishToGenerated(ownerId, parsed.sessionId(), parsed.fileId());
    }

    private String publishToGenerated(Long userId, Long sessionId, Long fileId) {
        try (InputStream stream = agentFileService.openFileStream(userId, sessionId, fileId)) {
            AgentFile file = agentFileMapper.selectById(fileId);
            if (file == null) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "attachment not found: sessionId=" + sessionId + " fileId=" + fileId
                );
            }
            String ext = extensionOf(file.getOriginalFilename(), file.getContentType());
            String relativeKey = "agent-attachments/" + userId + "/" + fileId + "-" + UUID.randomUUID() + ext;
            StoredAsset stored = assetStorageService.storeStream(
                    relativeKey,
                    stream,
                    file.getFileSize() == null ? -1 : file.getFileSize(),
                    file.getContentType()
            );
            return assetStorageService.workerAccessibleUrl(stored.publicUrl());
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "failed to publish agent attachment");
        }
    }

    private Optional<AgentFileRef> parseAgentFileRef(String value) {
        String pathOnly = value;
        int queryIdx = pathOnly.indexOf('?');
        if (queryIdx >= 0) {
            pathOnly = pathOnly.substring(0, queryIdx);
        }
        if (pathOnly.startsWith("http://") || pathOnly.startsWith("https://")) {
            int slash = pathOnly.indexOf('/', pathOnly.indexOf("://") + 3);
            pathOnly = slash >= 0 ? pathOnly.substring(slash) : pathOnly;
        }
        Matcher matcher = AGENT_FILE_PATH.matcher(pathOnly);
        if (!matcher.find()) {
            return Optional.empty();
        }
        Long sessionId = Long.parseLong(matcher.group(1));
        Long fileId = Long.parseLong(matcher.group(2));
        AgentFile file = agentFileMapper.selectById(fileId);
        Long userId = file == null ? null : file.getUserId();
        return Optional.of(new AgentFileRef(userId, sessionId, fileId));
    }

    private static String extensionOf(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (ext.length() <= 8) {
                return ext;
            }
        }
        if (contentType != null) {
            String lowered = contentType.toLowerCase(Locale.ROOT);
            if (lowered.contains("png")) return ".png";
            if (lowered.contains("jpeg") || lowered.contains("jpg")) return ".jpg";
            if (lowered.contains("webp")) return ".webp";
            if (lowered.contains("gif")) return ".gif";
        }
        return ".bin";
    }

    private record AgentFileRef(Long userId, Long sessionId, Long fileId) {
    }
}
