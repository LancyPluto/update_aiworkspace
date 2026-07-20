package com.aiminilab.aitoolmarket.agent.service.impl;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentArtifactRequest;
import com.aiminilab.aitoolmarket.agent.entity.AgentFile;
import com.aiminilab.aitoolmarket.agent.entity.AgentRun;
import com.aiminilab.aitoolmarket.agent.entity.AgentSession;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileChunkMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentFileMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentSessionMapper;
import com.aiminilab.aitoolmarket.config.AppProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFileServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final Long SESSION_ID = 11L;
    private static final Long RUN_ID = 13L;
    private static final Long FILE_ID = 17L;

    @TempDir
    Path tempDir;

    private AgentSessionMapper agentSessionMapper;
    private AgentRunMapper agentRunMapper;
    private AgentFileMapper agentFileMapper;
    private AgentFileChunkMapper agentFileChunkMapper;
    private AgentFileServiceImpl service;

    @BeforeEach
    void setUp() {
        agentSessionMapper = mock(AgentSessionMapper.class);
        agentRunMapper = mock(AgentRunMapper.class);
        agentFileMapper = mock(AgentFileMapper.class);
        agentFileChunkMapper = mock(AgentFileChunkMapper.class);
        AgentServiceClient agentServiceClient = mock(AgentServiceClient.class);
        AppProperties appProperties = new AppProperties();
        appProperties.getAgent().setFileStorageDir(tempDir.toString());
        service = new AgentFileServiceImpl(
                agentSessionMapper,
                agentRunMapper,
                agentFileMapper,
                agentFileChunkMapper,
                agentServiceClient,
                appProperties
        );
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadDeletesNewFileWhenTransactionRollsBack() {
        stubActiveSession();
        AtomicReference<Path> storedPath = failFileInsertAndCapturePath();
        TransactionSynchronizationManager.initSynchronization();

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "temporary notes".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.upload(USER_ID, SESSION_ID, multipartFile))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database insert failed");
        assertThat(storedPath.get()).exists();

        onlySynchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(storedPath.get()).doesNotExist();
    }

    @Test
    void artifactDeletesNewFileWhenTransactionRollsBack() {
        AgentRun run = new AgentRun();
        run.setId(RUN_ID);
        run.setSessionId(SESSION_ID);
        run.setUserId(USER_ID);
        when(agentRunMapper.findById(RUN_ID)).thenReturn(Optional.of(run));
        AtomicReference<Path> storedPath = failFileInsertAndCapturePath();
        TransactionSynchronizationManager.initSynchronization();

        CreateAgentArtifactRequest request = new CreateAgentArtifactRequest(
                "report.md",
                "Long task result",
                "text/markdown"
        );

        assertThatThrownBy(() -> service.createArtifactForRun(RUN_ID, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database insert failed");
        assertThat(storedPath.get()).exists();

        onlySynchronization().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(storedPath.get()).doesNotExist();
    }

    @Test
    void uploadRetainsNewFileWhenTransactionOutcomeIsUnknown() {
        stubActiveSession();
        AtomicReference<Path> storedPath = failFileInsertAndCapturePath();
        TransactionSynchronizationManager.initSynchronization();
        MockMultipartFile multipartFile = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "temporary notes".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.upload(USER_ID, SESSION_ID, multipartFile))
                .isInstanceOf(IllegalStateException.class);

        onlySynchronization().afterCompletion(TransactionSynchronization.STATUS_UNKNOWN);

        assertThat(storedPath.get()).exists();
    }

    @Test
    void deleteRemovesStoredFileOnlyAfterCommit() throws Exception {
        stubActiveSession();
        Path storedPath = tempDir.resolve(SESSION_ID.toString()).resolve("stored-notes.txt");
        Files.createDirectories(storedPath.getParent());
        Files.writeString(storedPath, "temporary notes", StandardCharsets.UTF_8);
        AgentFile file = new AgentFile();
        file.setId(FILE_ID);
        file.setSessionId(SESSION_ID);
        file.setUserId(USER_ID);
        file.setStoragePath(storedPath.toString());
        when(agentFileMapper.findByIdSessionAndUser(FILE_ID, SESSION_ID, USER_ID)).thenReturn(Optional.of(file));
        when(agentFileMapper.deleteByIdSessionAndUser(FILE_ID, SESSION_ID, USER_ID)).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();

        service.delete(USER_ID, SESSION_ID, FILE_ID);

        verify(agentFileChunkMapper).deleteByFileId(FILE_ID);
        verify(agentFileMapper).deleteByIdSessionAndUser(FILE_ID, SESSION_ID, USER_ID);
        assertThat(storedPath).exists();

        onlySynchronization().afterCommit();

        assertThat(storedPath).doesNotExist();
    }

    private void stubActiveSession() {
        AgentSession session = new AgentSession();
        session.setId(SESSION_ID);
        session.setUserId(USER_ID);
        session.setStatus("ACTIVE");
        when(agentSessionMapper.findByIdAndUserId(SESSION_ID, USER_ID)).thenReturn(Optional.of(session));
    }

    private AtomicReference<Path> failFileInsertAndCapturePath() {
        AtomicReference<Path> storedPath = new AtomicReference<>();
        doAnswer(invocation -> {
            AgentFile file = invocation.getArgument(0);
            storedPath.set(Path.of(file.getStoragePath()));
            throw new IllegalStateException("database insert failed");
        }).when(agentFileMapper).insertFile(any(AgentFile.class));
        return storedPath;
    }

    private TransactionSynchronization onlySynchronization() {
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        return TransactionSynchronizationManager.getSynchronizations().get(0);
    }
}
