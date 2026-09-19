package com.aiminilab.aitoolmarket.agent;

import com.aiminilab.aitoolmarket.agent.service.*;
import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Set RECOVERY_TEST_JDBC_URL for the same race tests against an isolated MySQL database. */
class AgentRecoveryConcurrencyTest {
    JdbcTemplate db;
    AgentRunRecoveryService recovery;
    AgentRunService runs;
    AgentExecutionFenceFilter fence;

    @BeforeEach void setup() {
        String url=System.getenv().getOrDefault("RECOVERY_TEST_JDBC_URL", "jdbc:h2:mem:recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var source=new DriverManagerDataSource(url,System.getenv().getOrDefault("RECOVERY_TEST_DB_USER","sa"),System.getenv().getOrDefault("RECOVERY_TEST_DB_PASSWORD",""));
        db=new JdbcTemplate(source);
        db.execute("DROP TABLE IF EXISTS agent_run_operations");
        db.execute("DROP TABLE IF EXISTS agent_run_recovery");
        db.execute("DROP TABLE IF EXISTS agent_run_execution_leases");
        db.execute("DROP TABLE IF EXISTS agent_runs");
        db.execute("CREATE TABLE agent_runs(id BIGINT PRIMARY KEY,status VARCHAR(40),updated_at TIMESTAMP)");
        db.execute("CREATE TABLE agent_run_execution_leases(run_id BIGINT PRIMARY KEY,owner_token VARCHAR(128),lease_expires_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
        db.execute("CREATE TABLE agent_run_recovery(run_id BIGINT PRIMARY KEY,attempts INT DEFAULT 0,next_attempt_at TIMESTAMP NULL,owner_token VARCHAR(128),last_error VARCHAR(1000),runtime_json TEXT,created_at TIMESTAMP,updated_at TIMESTAMP)");
        db.execute("CREATE TABLE agent_run_operations(run_id BIGINT,operation_key VARCHAR(240),response_json TEXT,created_at TIMESTAMP,PRIMARY KEY(run_id,operation_key))");
        db.update("INSERT INTO agent_runs VALUES (1,'RUNNING',?)",LocalDateTime.now().minusHours(1));
        var manager=new DataSourceTransactionManager(source);
        runs=mock(AgentRunService.class);
        recovery=new AgentRunRecoveryService(db,manager,mock(AgentRunMapper.class),mock(AgentToolCallMapper.class),runs,mock(AgentServiceClient.class),new com.fasterxml.jackson.databind.ObjectMapper(),5);
        fence=new AgentExecutionFenceFilter(db,manager,true);
    }

    @Test void twoScannersOnlyOneClaimsAndDuplicateNotificationOnlyOneAdopts() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        var gate=new CountDownLatch(1);
        try {
            Callable<String> claim=()->{gate.await(); return recovery.claim(1L,LocalDateTime.now().minusMinutes(15));};
            var a=pool.submit(claim); var b=pool.submit(claim); gate.countDown();
            String first=a.get(10,TimeUnit.SECONDS), second=b.get(10,TimeUnit.SECONDS);
            assertThat((first==null) != (second==null)).isTrue();
            String owner=first==null?second:first;
            assertThat(recovery.adopt(1L,owner,"runtime:one")).isTrue();
            assertThat(recovery.adopt(1L,owner,"runtime:two")).isFalse();
            assertThat(db.queryForObject("SELECT attempts FROM agent_run_recovery",Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void waitingConfirmationAndLiveLeaseNeverBecomeCandidates() {
        db.update("UPDATE agent_runs SET status='WAITING_USER_CONFIRMATION'");
        assertThat(recovery.candidates(LocalDateTime.now(),20)).isEmpty();
        assertThat(recovery.claim(1L,LocalDateTime.now())).isNull();
        db.update("UPDATE agent_runs SET status='RUNNING'");
        recovery.claim(1L,LocalDateTime.now());
        assertThat(recovery.candidates(LocalDateTime.now(),20)).isEmpty();
    }

    @Test void staleOwnerCannotWriteAndOperationReplayHasOneEffect() throws Exception {
        String owner=recovery.claim(1L,LocalDateTime.now());
        AtomicInteger effects=new AtomicInteger();
        assertThat(mutate("old-owner",null,effects).getStatus()).isEqualTo(409);
        assertThat(mutate(owner,"memory-call-1",effects).getStatus()).isEqualTo(200);
        assertThat(mutate(owner,"memory-call-1",effects).getContentAsString()).isEqualTo("{\"code\":\"SUCCESS\"}");
        assertThat(effects.get()).isEqualTo(1);
        db.update("UPDATE agent_run_execution_leases SET lease_expires_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP)");
        assertThat(mutate(owner,null,effects).getStatus()).isEqualTo(409);
    }

    @Test void retryBudgetSurvivesNewServiceAndDoesNotCountContention() {
        String owner=recovery.claim(1L,LocalDateTime.now());
        assertThat(recovery.claim(1L,LocalDateTime.now())).isNull();
        recovery.outcome(1L,owner,"query_unavailable",false,false);
        assertThat(recovery.candidates(LocalDateTime.now(),20)).isEmpty();
        db.update("UPDATE agent_run_recovery SET attempts=5,next_attempt_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP)");
        assertThat(recovery.claim(1L,LocalDateTime.now())).isNull();
        verify(runs).failRun(eq(1L),argThat(r->r.errorCode().equals("AGENT_RECOVERY_EXHAUSTED")));
    }

    @Test void mapperLeaseUsesDatabaseClockAndCannotReviveExpiredOwner() {
        var environment = new org.apache.ibatis.mapping.Environment("recovery", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), db.getDataSource());
        var configuration = new org.apache.ibatis.session.Configuration(environment);
        configuration.addMapper(AgentRunMapper.class);
        try (var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            var mapper = session.getMapper(AgentRunMapper.class);
            var now = mapper.databaseNow();
            mapper.acquireExecutionLease(1L,"first",now.plusSeconds(60),now);
            mapper.acquireExecutionLease(1L,"contender",now.plusSeconds(60),now);
            assertThat(mapper.selectExecutionLeaseOwner(1L)).isEqualTo("first");
            db.update("UPDATE agent_run_execution_leases SET lease_expires_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP)");
            assertThat(mapper.renewExecutionLease(1L,"first",now.plusSeconds(60),now)).isZero();
            mapper.acquireExecutionLease(1L,"contender",now.plusSeconds(60),now);
            assertThat(mapper.selectExecutionLeaseOwner(1L)).isEqualTo("contender");
        }
    }

    MockHttpServletResponse mutate(String owner,String key,AtomicInteger effects) throws Exception {
        var request=new MockHttpServletRequest("POST","/api/internal/v1/agent/runs/1/events");
        request.addHeader("X-Agent-Run-Id","1"); request.addHeader("X-Agent-Execution-Owner",owner);
        if (key!=null) request.addHeader("X-Agent-Operation-Key",key);
        var response=new MockHttpServletResponse();
        fence.doFilter(request,response,(req,res)->{ effects.incrementAndGet(); res.getWriter().write("{\"code\":\"SUCCESS\"}"); });
        return response;
    }
}
