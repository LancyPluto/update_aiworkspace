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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AgentFileServiceImpl implements AgentFileService {

    private static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024;
    private static final int DEFAULT_FILE_LIST_SIZE = 20;
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
        file.setStoragePath(storeFile(sessionId, file.getOriginalFilename(), bytes));
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
            file.setStatus("FAILED");
            file.setErrorMessage(exception.getMessage());
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
        file.setStoragePath(storeFile(run.getSessionId(), filename, bytes));
        file.setStatus("READY");
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
        List<AgentFileResponse> files = agentFileMapper.findBySession(userId, sessionId, DEFAULT_FILE_LIST_SIZE)
                .stream()
                .map(AgentFileResponse::from)
                .toList();
        return new PageResponse<>(files, files.size(), 1, DEFAULT_FILE_LIST_SIZE, files.size() == DEFAULT_FILE_LIST_SIZE);
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
        try {
            Path root = Path.of(appProperties.getAgent().getFileStorageDir()).toAbsolutePath().normalize();
            Path sessionDir = root.resolve(String.valueOf(sessionId)).normalize();
            Files.createDirectories(sessionDir);
            Path stored = sessionDir.resolve(UUID.randomUUID() + "-" + filename).normalize();
            if (!stored.startsWith(root)) {
                throw new BusinessException(ErrorCode.PARAM_ERROR, "invalid filename");
            }
            Files.write(stored, bytes);
            return stored.toString();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "could not store uploaded file");
        }
    }

    private String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "upload.bin" : Path.of(filename).getFileName().toString();
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
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
