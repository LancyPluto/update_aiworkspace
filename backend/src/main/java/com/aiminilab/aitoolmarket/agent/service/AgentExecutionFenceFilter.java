package com.aiminilab.aitoolmarket.agent.service;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Hold the same run-row lock as claim/cancel until the mutation commits, including nested services. */
@Component
public class AgentExecutionFenceFilter extends OncePerRequestFilter {
    private static final Pattern RUN = Pattern.compile("/api/internal/v1/agent/runs/(\\d+)(?:/.*)?");
    private static final Pattern CALL = Pattern.compile("/api/internal/v1/agent/tool-calls/(\\d+)(?:/.*)?");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final boolean required;
    public AgentExecutionFenceFilter(JdbcTemplate db, PlatformTransactionManager manager,
            @Value("${app.agent.auto-recovery-enabled:false}") boolean required) {
        this.db=db; this.tx=new TransactionTemplate(manager); this.required=required;
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path=request.getRequestURI();
        if (!path.startsWith("/api/internal/v1/") || "GET".equals(request.getMethod())
                || path.contains("/execution-lease") || path.endsWith("/langgraph-checkpoints/search")) {
            chain.doFilter(request,response); return;
        }
        var runMatch=RUN.matcher(path); var callMatch=CALL.matcher(path);
        Long target=runMatch.matches()?Long.valueOf(runMatch.group(1)):null;
        if (callMatch.matches()) {
            var ids=db.queryForList("SELECT run_id FROM agent_tool_calls WHERE id=?",Long.class,Long.valueOf(callMatch.group(1)));
            if (!ids.isEmpty()) target=ids.get(0);
        }
        String owner=request.getHeader("X-Agent-Execution-Owner");
        String runHeader=request.getHeader("X-Agent-Run-Id");
        if (owner==null || runHeader==null) {
            if (owner!=null || runHeader!=null) { reject(response); return; }
            if (required && (target!=null || path.equals("/api/internal/v1/tasks") || (path.contains("/agent/workspaces/") && !path.endsWith("/retrieve")))) { reject(response); return; }
            chain.doFilter(request,response); return;
        }
        Long runId;
        try { runId=Long.valueOf(runHeader); } catch (NumberFormatException e) { reject(response); return; }
        if (target!=null && !target.equals(runId)) { reject(response); return; }
        ContentCachingResponseWrapper buffered=new ContentCachingResponseWrapper(response);
        try {
            tx.executeWithoutResult(status -> {
                var rows=db.queryForList("SELECT status FROM agent_runs WHERE id=? FOR UPDATE",String.class,runId);
                boolean active=!rows.isEmpty() && Set.of("CREATED","RUNNING","WAITING_USER_CONFIRMATION").contains(rows.get(0));
                // Allow cleanup/audit by the completing owner, never new dispatches from a terminal run.
                boolean cleanup=path.endsWith("/model-request-snapshots") || ("DELETE".equals(request.getMethod()) && path.contains("checkpoint"));
                boolean owns=db.queryForObject("SELECT COUNT(*) FROM agent_run_execution_leases WHERE run_id=? AND owner_token=? AND lease_expires_at>CURRENT_TIMESTAMP",Integer.class,runId,owner)>0;
                boolean waiting=!rows.isEmpty() && "WAITING_USER_CONFIRMATION".equals(rows.get(0));
                boolean dispatch=path.equals("/api/internal/v1/tasks") || path.endsWith("/delegate-workflow") || path.endsWith("/tool-calls")
                        || path.endsWith("/complete") || path.endsWith("/fail") || path.endsWith("/artifacts") || path.contains("/memory");
                try {
                    if (!owns || (!active && !cleanup) || (waiting && dispatch)) { reject(buffered); return; }
                    String key=request.getHeader("X-Agent-Operation-Key");
                    if (key!=null && key.length()>240) { reject(buffered); return; }
                    if (key!=null) {
                        var cached=db.queryForList("SELECT response_json FROM agent_run_operations WHERE run_id=? AND operation_key=?",String.class,runId,key);
                        if (!cached.isEmpty()) { buffered.setContentType("application/json"); buffered.setCharacterEncoding(StandardCharsets.UTF_8.name()); buffered.getWriter().write(cached.get(0)); return; }
                    }
                    chain.doFilter(request,buffered);
                    if (buffered.getStatus()>=400) { status.setRollbackOnly(); return; }
                    if (key!=null) db.update("INSERT INTO agent_run_operations(run_id,operation_key,response_json,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP)",runId,key,new String(buffered.getContentAsByteArray(),StandardCharsets.UTF_8));
                } catch (IOException | ServletException e) { throw new IllegalStateException(e); }
            });
        } catch (RuntimeException e) { buffered.resetBuffer(); throw e; }
        buffered.copyBodyToResponse();
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(409); response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"AGENT_EXECUTION_LEASE_LOST\",\"message\":\"Execution lease no longer valid\"}");
    }
}
