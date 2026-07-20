package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseChunk;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileParseResult;
import com.aiminilab.aitoolmarket.agent.dto.AgentFileResponse;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentArtifactRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentFileChunk;
import com.aiminilab.aitoolmarket.agent.entity.AgentSession;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileChunkMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSessionMapper;
import com.aiminilab.aitoolmarket.agent.service.AgentFileService;
import com.aiminilab.aitoolmarket.common.dto.PageResponse;
import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgentFileServiceImpl implements AgentFileService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentFileServiceImpl.class);
    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_FILE_LIST_SIZE = 20;
    private static final int RECENT_MEDIA_LIST_SIZE = 60;
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 120;

    private final AgentSessionMapper agentSessionMapper;
    private final AgentRunMapper agentRunMapper;
    private final AgentFileMapper agentFileMapper;
    private final AgentFileChunkMapper agentFileChunkMapper;
    private final AgentServiceClient agentServiceClient;
    private final AppProperties appProperties;

    public AgentFileServiceImpl(
            AgentSessionMapper agentSessionMapper,
            AgentRunMapper agentRunMapper,
            AgentFileMapper agentFileMapper,
            AgentFileChunkMapper agentFileChunkMapper,
            AgentServiceClient agentServiceClient,
            AppProperties appProperties
    ) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentRunMapper = agentRunMapper;
        this.agentFileMapper = agentFileMapper;
        this.agentFileChunkMapper = agentFileChunkMapper;
        this.agentServiceClient = agentServiceClient;
        this.appProperties = appProperties;
    }

    @Override
    @Transactional
    public AgentFileResponse upload(Long userId, Long sessionId, MultipartFile multipartFile) {
        AgentSession session = findActiveSession(userId, sessionId);
        if (multipartFile == null || multipartFile.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "file is required");
        }
        if (multipartFile.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "file is too large");
        }

        byte[] bytes = readBytes(multipartFile);
        LocalDateTime now = LocalDateTime.now();
        AgentFile file = new AgentFile();
        file.setSessionId(session.getId());
        file.setUserId(userId);
        file.setOriginalFilename(safeFilename(multipartFile.getOriginalFilename()));
        file.setContentType(multipartFile.getContentType());
        file.setFileSize(multipartFile.getSize());
        String storagePath = storeFile(sessionId, file.getOriginalFilename(), bytes);
        file.setStoragePath(storagePath);
        registerRollbackCleanup(storagePath);
        file.setStatus("PARSING");
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        agentFileMapper.insertFile(file);

        try {
            AgentFileParseResult parseResult = agentServiceClient.parseFile(file.getOriginalFilename(), file.getContentType(), bytes);
            file.setStatus("READY");
            file.setExtractedText(parseResult == null || parseResult.text() == null ? "" : parseResult.text());
            file.setErrorMessage(null);
            writeChunks(file, parseResult);
        } catch (RuntimeException exception) {
            if (isImageFile(file.getOriginalFilename(), file.getContentType())) {
                String label = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
                String fallbackText = "[用户已上传图片：" + label + "]\n"
                        + "该图片已随当前消息提交，可作为图生视频/图像工具的首帧或参考图输入；"
                        + "请勿再要求用户重新上传或提供图片链接。";
                file.setStatus("READY");
                file.setExtractedText(fallbackText);
                file.setErrorMessage(null);
                writeChunks(file, AgentFileParseResult.fromText(label, fallbackText));
            } else {
                file.setStatus("FAILED");
                file.setErrorMessage(exception.getMessage());
            }
        }
        file.setUpdatedAt(LocalDateTime.now());
        agentFileMapper.updateParseResult(file.getId(), file.getStatus(), file.getExtractedText(), file.getErrorMessage(), file.getUpdatedAt());
        return AgentFileResponse.from(file);
    }

    @Override
    @Transactional
    public AgentFileResponse createArtifactForRun(Long runId, CreateAgentArtifactRequest request) {
        AgentRun run = agentRunMapper.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "content is required");
        }

        String filename = safeFilename(request.filename());
        String content = request.content();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        LocalDateTime now = LocalDateTime.now();

        AgentFile file = new AgentFile();
        file.setSessionId(run.getSessionId());
        file.setUserId(run.getUserId());
        file.setOriginalFilename(filename);
        file.setContentType(request.contentType());
        file.setFileSize((long) bytes.length);
        String storagePath = storeFile(run.getSessionId(), filename, bytes);
        file.setStoragePath(storagePath);
        registerRollbackCleanup(storagePath);
        file.setStatus("READY");
        file.setAttachedRunId(runId);
        file.setExtractedText(content);
        file.setErrorMessage(null);
        file.setCreatedAt(now);
        file.setUpdatedAt(now);
        agentFileMapper.insertFile(file);
        writeChunks(file, AgentFileParseResult.fromText(filename, content));
        return AgentFileResponse.from(file);
    }

    @Override
    public PageResponse<AgentFileResponse> list(Long userId, Long sessionId) {
        findActiveSession(userId, sessionId);
        List<AgentFileResponse> files = agentFileMapper.findPendingBySession(userId, sessionId, DEFAULT_FILE_LIST_SIZE)
                .stream()
                .map(AgentFileResponse::from)
                .toList();
        return new PageResponse<>(files, files.size(), 1, DEFAULT_FILE_LIST_SIZE, files.size() == DEFAULT_FILE_LIST_SIZE);
    }

    @Override
    public PageResponse<AgentFileResponse> listRecentMedia(Long userId, Long sessionId) {
        findActiveSession(userId, sessionId);
        List<AgentFileResponse> files = agentFileMapper.findRecentMediaByUser(userId, RECENT_MEDIA_LIST_SIZE)
                .stream()
                .map(AgentFileResponse::from)
                .toList();
        return new PageResponse<>(files, files.size(), 1, RECENT_MEDIA_LIST_SIZE, files.size() == RECENT_MEDIA_LIST_SIZE);
    }

    @Override
    @Transactional
    public void delete(Long userId, Long sessionId, Long fileId) {
        findActiveSession(userId, sessionId);
        AgentFile file = agentFileMapper.findByIdSessionAndUser(fileId, sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Agent file not found"));
        agentFileChunkMapper.deleteByFileId(file.getId());
        int affected = agentFileMapper.deleteByIdSessionAndUser(fileId, sessionId, userId);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent file not found");
        }
        deleteStoredFileAfterCommit(file.getStoragePath());
    }

    @Override
    public InputStream openFileStream(Long userId, Long sessionId, Long fileId) {
        findActiveSession(userId, sessionId);
        AgentFile file = agentFileMapper.findByIdSessionAndUser(fileId, sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Agent file not found"));
        String storagePath = file.getStoragePath();
        if (storagePath == null || storagePath.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Agent file not stored");
        }
        try {
            Path stored = Path.of(storagePath).toAbsolutePath().normalize();
            Path root = Path.of(appProperties.getAgent().getFileStorageDir()).toAbsolutePath().normalize();
            if (!stored.startsWith(root)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid storage path");
            }
            return Files.newInputStream(stored);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "could not read stored file");
        }
    }

    @Override
    public AgentFileResponse getMeta(Long userId, Long sessionId, Long fileId) {
        findActiveSession(userId, sessionId);
        AgentFile file = agentFileMapper.findByIdSessionAndUser(fileId, sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PARAM_ERROR, "Agent file not found"));
        return AgentFileResponse.from(file);
    }

    @Override
    @Transactional
    public void attachPendingFilesToRun(Long userId, Long sessionId, Long runId, List<Long> fileIds) {
        findActiveSession(userId, sessionId);
        LocalDateTime now = LocalDateTime.now();
        if (fileIds == null || fileIds.isEmpty()) {
            agentFileMapper.attachAllPendingFilesToRun(userId, sessionId, runId, now);
            return;
        }
        for (Long fileId : fileIds) {
            if (fileId == null) {
                continue;
            }
            agentFileMapper.attachPendingFileToRun(userId, sessionId, runId, fileId, now);
        }
    }

    private AgentSession findActiveSession(Long userId, Long sessionId) {
        AgentSession session = agentSessionMapper.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "Agent session not found"));
        if (!"ACTIVE".equals(session.getStatus())) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND, "Agent session is not active");
        }
        return session;
    }

    private byte[] readBytes(MultipartFile multipartFile) {
        try {
            return multipartFile.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "could not read uploaded file");
        }
    }

    private String storeFile(Long sessionId, String filename, byte[] bytes) {
        Path stored = null;
        boolean created = false;
        try {
            stored = resolveStoredPath(sessionId, filename);
            Files.createFile(stored);
            created = true;
            Files.write(stored, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return stored.toString();
        } catch (IOException exception) {
            if (created) {
                deleteStoredFile(stored == null ? null : stored.toString(), "failed file write");
            }
            throw new BusinessException(ErrorCode.PARAM_ERROR, "could not store uploaded file");
        }
    }

    private void registerRollbackCleanup(String storagePath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            LOGGER.error("Transaction synchronization is not active; removing newly stored agent file: storagePath={}",
                    storagePath);
            deleteStoredFile(storagePath, "missing transaction synchronization");
            throw new IllegalStateException("Transaction synchronization is required for agent file creation");
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        deleteStoredFile(storagePath, "transaction rollback");
                    } else if (status == TransactionSynchronization.STATUS_UNKNOWN) {
                        LOGGER.warn("Agent file transaction outcome is unknown; retaining stored file: storagePath={}",
                                storagePath);
                    }
                }
            });
        } catch (RuntimeException exception) {
            deleteStoredFile(storagePath, "rollback cleanup registration failure");
            throw exception;
        }
    }

    private void deleteStoredFileAfterCommit(String storagePath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteStoredFile(storagePath, "database deletion without transaction synchronization");
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteStoredFile(storagePath, "database commit");
            }
        });
    }

    private void deleteStoredFile(String storagePath, String phase) {
        if (storagePath == null || storagePath.isBlank()) {
            LOGGER.warn("Could not delete agent file during {}: storage path is blank", phase);
            return;
        }
        try {
            Path stored = Path.of(storagePath).toAbsolutePath().normalize();
            Path root = Path.of(appProperties.getAgent().getFileStorageDir()).toAbsolutePath().normalize();
            if (stored.equals(root) || !stored.startsWith(root)) {
                LOGGER.warn("Refusing to delete agent file outside configured storage during {}: storagePath={}, root={}",
                        phase, stored, root);
                return;
            }
            Files.deleteIfExists(stored);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not delete agent file during {}: storagePath={}", phase, storagePath, exception);
        }
    }

    private Path resolveStoredPath(Long sessionId, String filename) throws IOException {
        Path root = Path.of(appProperties.getAgent().getFileStorageDir()).toAbsolutePath().normalize();
        Path sessionDir = root.resolve(String.valueOf(sessionId)).normalize();
        Files.createDirectories(sessionDir);
        Path stored = sessionDir.resolve(UUID.randomUUID() + "-" + filename).normalize();
        if (!stored.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid filename");
        }
        return stored;
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "upload.bin" : Path.of(filename).getFileName().toString();
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private boolean isImageFile(String filename, String contentType) {
        String lowerName = filename == null ? "" : filename.toLowerCase();
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        if (lowerType.startsWith("image/")) {
            return true;
        }
        return lowerName.endsWith(".png")
                || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".webp")
                || lowerName.endsWith(".gif")
                || lowerName.endsWith(".bmp")
                || lowerName.endsWith(".heic")
                || lowerName.endsWith(".heif")
                || lowerName.endsWith(".avif");
    }

    private void writeChunks(AgentFile file, AgentFileParseResult parseResult) {
        agentFileChunkMapper.deleteByFileId(file.getId());
        LocalDateTime now = LocalDateTime.now();
        List<AgentFileParseChunk> parsedChunks = parseResult == null || parseResult.chunks() == null
                ? List.of()
                : parseResult.chunks().stream()
                        .filter(chunk -> chunk != null && chunk.content() != null && !chunk.content().isBlank())
                        .toList();
        if (!parsedChunks.isEmpty()) {
            for (int index = 0; index < parsedChunks.size(); index++) {
                AgentFileParseChunk parsedChunk = parsedChunks.get(index);
                insertChunk(file, parsedChunk.content().strip(),
                        parsedChunk.chunkIndex() == null ? index : parsedChunk.chunkIndex(), now);
            }
            return;
        }
        List<String> chunks = splitChunks(parseResult == null ? "" : parseResult.text());
        for (int index = 0; index < chunks.size(); index++) {
            insertChunk(file, chunks.get(index), index, now);
        }
    }

    private void insertChunk(AgentFile file, String content, int chunkIndex, LocalDateTime now) {
        AgentFileChunk chunk = new AgentFileChunk();
        chunk.setFileId(file.getId());
        chunk.setSessionId(file.getSessionId());
        chunk.setUserId(file.getUserId());
        chunk.setChunkIndex(chunkIndex);
        chunk.setContentText(content);
        chunk.setMetadataJson("{\"source\":\"" + escapeJson(file.getOriginalFilename()) + "\",\"chunkIndex\":" + chunkIndex + "}");
        chunk.setCreatedAt(now);
        agentFileChunkMapper.insertChunk(chunk);
    }

    private List<String> splitChunks(String text) {
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.contains("\n\n")) {
            List<String> paragraphs = java.util.Arrays.stream(normalized.split("\\n\\s*\\n"))
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .toList();
            if (paragraphs.stream().allMatch(value -> value.length() <= CHUNK_SIZE)) {
                return paragraphs;
            }
        }
        List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + CHUNK_SIZE);
            chunks.add(normalized.substring(start, end).strip());
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(0, end - CHUNK_OVERLAP);
        }
        return chunks;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
