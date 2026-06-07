package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final AppProperties appProperties;

    public AgentAttachmentUrlResolver(AgentFileMapper agentFileMapper,
                                      AgentFileService agentFileService,
                                      AppProperties appProperties) {
        this.agentFileMapper = agentFileMapper;
        this.agentFileService = agentFileService;
        this.appProperties = appProperties;
    }

    public String resolveForWorker(Long userId, String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("/generated/")) {
            return workerAccessibleUrl(trimmed);
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
            Path root = Path.of(appProperties.getGeneratedMediaDir())
                    .resolve("agent-attachments")
                    .resolve(String.valueOf(userId))
                    .toAbsolutePath()
                    .normalize();
            Files.createDirectories(root);
            AgentFile file = agentFileMapper.selectById(fileId);
            if (file == null) {
                throw new BusinessException(
                        ErrorCode.PARAM_ERROR,
                        "attachment not found: sessionId=" + sessionId + " fileId=" + fileId
                );
            }
            String ext = extensionOf(file.getOriginalFilename(), file.getContentType());
            Path stored = root.resolve(fileId + "-" + UUID.randomUUID() + ext).normalize();
            if (!stored.startsWith(root)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid attachment path");
            }
            Files.copy(stream, stored);
            Path mediaRoot = Path.of(appProperties.getGeneratedMediaDir()).toAbsolutePath().normalize();
            String relative = mediaRoot.relativize(stored).toString().replace('\\', '/');
            return workerAccessibleUrl("/generated/" + relative);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "failed to publish agent attachment");
        }
    }

    private String workerAccessibleUrl(String publicPath) {
        String path = publicPath.startsWith("/") ? publicPath : "/" + publicPath;
        String base = appProperties.getAgent().getWorkerMediaBaseUrl();
        if (base == null || base.isBlank()) {
            return path;
        }
        return base.replaceAll("/+$", "") + path;
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
