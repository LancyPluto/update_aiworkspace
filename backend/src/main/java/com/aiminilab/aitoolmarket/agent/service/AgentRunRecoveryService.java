package com.aiminilab.aitoolmarket.agent.service;

import com.aiminilab.aitoolmarket.agent.client.AgentServiceClient;
import com.aiminilab.aitoolmarket.agent.dto.FailAgentRunRequest;
import com.aiminilab.aitoolmarket.agent.dto.CreateAgentRunEventRequest;
import com.aiminilab.aitoolmarket.agent.mapper.AgentRunMapper;
import com.aiminilab.aitoolmarket.agent.mapper.AgentToolCallMapper;
import com.aiminilab.aitoolmarket.agent.dto.AgentToolCallResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;

/** Durable dispatch: an accepted HTTP notification is never treated as execution completion. */
@Service
public class AgentRunRecoveryService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final AgentRunMapper runs;
    private final AgentToolCallMapper calls;
    private final AgentRunService service;
    private final AgentServiceClient client;
    private final int maxAttempts;
    private final ObjectMapper json;

    public AgentRunRecoveryService(JdbcTemplate db, PlatformTransactionManager manager, AgentRunMapper runs,
            AgentToolCallMapper calls, AgentRunService service, AgentServiceClient client, ObjectMapper json,
            @Value("${app.agent.auto-recovery-max-attempts:5}") int maxAttempts) {
        this.db = db; this.tx = new TransactionTemplate(manager); this.runs = runs;
        this.calls = calls; this.service = service; this.client = client;
        this.maxAttempts = Math.max(1, maxAttempts); this.json=json;
    }

    public boolean adopt(Long runId, String expected, String owner) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String state = db.queryForObject("SELECT status FROM agent_runs WHERE id=? FOR UPDATE", String.class, runId);
            if (!Set.of("CREATED", "RUNNING").contains(state) || owner == null || owner.isBlank() || owner.equals(expected)) return false;
            return db.update("UPDATE agent_run_execution_leases SET owner_token=?,updated_at=CURRENT_TIMESTAMP WHERE run_id=? AND owner_token=? AND lease_expires_at>CURRENT_TIMESTAMP", owner, runId, expected)==1;
        }));
    }

    public List<Long> candidates(LocalDateTime cutoff, int limit) {
        return db.queryForList("""
            SELECT r.id FROM agent_runs r
            LEFT JOIN agent_run_execution_leases l ON l.run_id=r.id
            LEFT JOIN agent_run_recovery x ON x.run_id=r.id
            WHERE r.status IN ('CREATED','RUNNING')
              AND ((l.run_id IS NOT NULL AND l.lease_expires_at <= CURRENT_TIMESTAMP)
                   OR (l.run_id IS NULL AND r.updated_at < ?))
              AND (x.next_attempt_at IS NULL OR x.next_attempt_at <= CURRENT_TIMESTAMP)
            ORDER BY COALESCE(x.next_attempt_at,r.updated_at),r.id LIMIT ?
            """, Long.class, cutoff, limit);
    }

    public String claim(Long runId, LocalDateTime cutoff) {
        return tx.execute(status -> {
            var row = db.queryForMap("SELECT status,updated_at FROM agent_runs WHERE id=? FOR UPDATE", runId);
            if (!Set.of("CREATED", "RUNNING").contains(row.get("status"))) return null;
            var lease = db.queryForList("SELECT owner_token FROM agent_run_execution_leases WHERE run_id=? AND lease_expires_at>CURRENT_TIMESTAMP", runId);
            if (!lease.isEmpty()) return null;
            if (db.queryForObject("SELECT COUNT(*) FROM agent_run_execution_leases WHERE run_id=?", Integer.class, runId)==0
                    && ((java.sql.Timestamp)row.get("updated_at")).toLocalDateTime().isAfter(cutoff)) return null;
            ensureRecord(runId);
            if (db.queryForObject("SELECT COUNT(*) FROM agent_run_recovery WHERE run_id=? AND next_attempt_at>CURRENT_TIMESTAMP", Integer.class, runId)>0) return null;
            int attempts = db.queryForObject("SELECT attempts FROM agent_run_recovery WHERE run_id=?", Integer.class, runId);
            if (attempts >= maxAttempts) {
                Metrics.counter("agent.recovery.exhausted").increment();
                service.failRun(runId, failure(runId, "AGENT_RECOVERY_EXHAUSTED", "自动恢复次数已耗尽，已保留原任务记录。"));
                return null;
            }
            runs.markRunningIfStatus(runId, "CREATED", LocalDateTime.now());
            String owner = "recovery:" + UUID.randomUUID();
            db.update("DELETE FROM agent_run_execution_leases WHERE run_id=?", runId);
            db.update("INSERT INTO agent_run_execution_leases(run_id,owner_token,lease_expires_at,created_at,updated_at) VALUES (?,?,TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", runId, owner);
            db.update("UPDATE agent_run_recovery SET attempts=attempts+1,owner_token=?,next_attempt_at=TIMESTAMPADD(MINUTE,?,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE run_id=?",
                    owner, 1L << Math.min(attempts, 3), runId);
            service.appendEvent(runId,new CreateAgentRunEventRequest("run.recovery_scheduled", "已安排自动恢复", Map.of("attempt",attempts+1)));
            Metrics.counter("agent.recovery.attempts").increment();
            return owner;
        });
    }

    public void dispatch(Long runId, LocalDateTime cutoff) {
        String owner = claim(runId, cutoff);
        if (owner == null) return;
        try { client.recoverRun(runId, owner); }
        catch (RuntimeException e) {
            // Notification may have reached the runtime. Keep its lease until expiry.
            db.update("UPDATE agent_run_recovery SET last_error=?,updated_at=CURRENT_TIMESTAMP WHERE run_id=? AND owner_token=?",
                    "RECOVERY_DISPATCH_UNCERTAIN", runId, owner);
        }
    }

    public void ensureRecord(Long runId) {
        db.update("INSERT INTO agent_run_recovery(run_id,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE run_id=VALUES(run_id)", runId);
    }

    public Map<String,Object> snapshot(Long runId) {
        Map<String,Object> result = new LinkedHashMap<>();
        var run = runs.findById(runId).orElseThrow();
        result.put("status", run.getStatus());
        result.put("checkpoint", service.getLangGraphCheckpoint(runId, "agent-run:"+runId, "", null));
        result.put("toolCalls", db.queryForList("SELECT id FROM agent_tool_calls WHERE run_id=? ORDER BY id", Long.class, runId)
                .stream().map(id -> AgentToolCallResponse.from(calls.selectDetailById(id))).toList());
        result.put("confirmations", db.query("SELECT call_id,tool_code,approved_at FROM agent_run_confirmations WHERE run_id=?", (rs,n) -> {
            Map<String,Object> c = new LinkedHashMap<>(); c.put("callId",rs.getString(1)); c.put("toolCode",rs.getString(2));
            c.put("approvedAt",rs.getTimestamp(3)); return c;
        }, runId));
        result.put("tasks", db.queryForList("SELECT t.id,t.status,c.id AS tool_call_id FROM ai_tasks t JOIN agent_tool_calls c ON c.task_id=t.id WHERE c.run_id=?",runId));
        var states = db.queryForList("SELECT runtime_json FROM agent_run_recovery WHERE run_id=?", String.class, runId);
        result.put("runtimeJson", states.isEmpty() ? null : states.get(0));
        result.put("hasStarted", db.queryForObject("SELECT COUNT(*) FROM agent_run_events WHERE run_id=? AND event_type='runtime_settings.applied'", Integer.class, runId)>0);
        result.put("hasOperations", db.queryForObject("SELECT COUNT(*) FROM agent_run_operations WHERE run_id=?", Integer.class, runId)>0);
        return result;
    }

    private FailAgentRunRequest failure(Long runId, String code, String message) {
        int consumed=0, prompt=0, completion=0;
        var rows=db.queryForList("SELECT runtime_json FROM agent_run_recovery WHERE run_id=?",String.class,runId);
        if (!rows.isEmpty() && rows.get(0)!=null) {
            try {
                var state=json.readTree(rows.get(0));
                consumed=state.path("budget").path("consumed_credits").asInt(0);
                prompt=state.path("usage").path("promptTokens").asInt(0);
                completion=state.path("usage").path("completionTokens").asInt(0);
            } catch (Exception ignored) { /* Missing legacy counters cannot be fabricated. */ }
        }
        return new FailAgentRunRequest(code,message,consumed,prompt,completion);
    }

    public void saveRuntime(Long runId, String json) {
        ensureRecord(runId);
        db.update("UPDATE agent_run_recovery SET runtime_json=?,updated_at=CURRENT_TIMESTAMP WHERE run_id=?", json, runId);
    }

    public void outcome(Long runId, String owner, String error, boolean permanent, boolean waiting) {
        // Caller holds the run lock and validates the owner through the execution fence.
        ensureRecord(runId);
        if (waiting) {
            db.update("UPDATE agent_runs SET status='WAITING_USER_CONFIRMATION',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='RUNNING'", runId);
        } else if (permanent) {
            service.failRun(runId, failure(runId, "AGENT_RECOVERY_UNSAFE", error));
        }
        db.update("UPDATE agent_run_recovery SET last_error=?,updated_at=CURRENT_TIMESTAMP WHERE run_id=?", error, runId);
        if (!permanent && !waiting) {
            service.appendEvent(runId,new CreateAgentRunEventRequest("run.recovery_retry", "等待下次安全恢复", Map.of("reason",error==null?"unknown":error)));
            db.update("UPDATE agent_run_execution_leases SET lease_expires_at=CURRENT_TIMESTAMP WHERE run_id=? AND owner_token=?", runId, owner);
        }
    }
}
