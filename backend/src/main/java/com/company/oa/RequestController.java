package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class RequestController {
  final JdbcTemplate db;
  final WorkflowService workflow;

  RequestController(JdbcTemplate db, WorkflowService workflow) {
    this.db = db;
    this.workflow = workflow;
  }

  @GetMapping("/health")
  Object health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of("status", "正常");
  }

  @GetMapping("/leave-types")
  Object types() {
    return db.queryForList("SELECT * FROM leave_types WHERE enabled ORDER BY id");
  }

  @GetMapping("/balances")
  Object balances(HttpServletRequest req) {
    return db.queryForList(
        "SELECT b.*,t.name type_name FROM balances b JOIN leave_types t ON t.id=b.type_id WHERE"
            + " b.user_id=? ORDER BY b.year DESC",
        Auth.actor(req).id());
  }

  @PostMapping("/requests")
  Object submit(@Valid @RequestBody WorkflowService.Application in, HttpServletRequest req) {
    return Map.of("id", workflow.submit(in, Auth.actor(req)));
  }

  @GetMapping("/requests")
  Object list(
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(required = false) Long userId,
      @RequestParam(defaultValue = "false") boolean inbox,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    Actor a = Auth.actor(req);
    Query query = requestQuery(a, status, kind, userId, inbox);
    query.params().add(Math.max(0, offset));
    return db.queryForList(
        query.sql() + " ORDER BY r.id DESC LIMIT 200 OFFSET ?", query.params().toArray());
  }

  record Query(String sql, List<Object> params) {}

  Query requestQuery(Actor a, String status, String kind, Long userId, boolean inbox) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT r.*,u.name user_name,u.employee_no,t.name type_name,(SELECT approver_id FROM"
                + " approval_steps WHERE request_id=r.id AND step=r.current_step) approver_id FROM"
                + " requests r JOIN users u ON u.id=r.user_id LEFT JOIN leave_types t ON"
                + " t.id=r.type_id WHERE 1=1");
    List<Object> p = new ArrayList<>();
    if (!a.admin()) {
      sql.append(
          " AND (r.user_id=? OR EXISTS(SELECT 1 FROM approval_steps s WHERE s.request_id=r.id AND"
              + " s.approver_id=?))");
      p.add(a.id());
      p.add(a.id());
    }
    if (inbox) {
      sql.append(
          " AND r.status='PENDING' AND EXISTS(SELECT 1 FROM approval_steps s WHERE"
              + " s.request_id=r.id AND s.step=r.current_step AND s.approver_id=?)");
      p.add(a.id());
    }
    if (!status.isBlank()) {
      sql.append(" AND r.status=?");
      p.add(status);
    }
    if (!kind.isBlank()) {
      sql.append(" AND r.kind=?");
      p.add(kind);
    }
    if (userId != null) {
      sql.append(" AND r.user_id=?");
      p.add(userId);
    }
    return new Query(sql.toString(), p);
  }

  @GetMapping("/requests/{id}")
  Object detail(@PathVariable long id, HttpServletRequest req) {
    var r = new LinkedHashMap<>(workflow.get(id, Auth.actor(req), false));
    r.put(
        "steps",
        db.queryForList(
            "SELECT s.*,u.name approver_name FROM approval_steps s JOIN users u ON"
                + " u.id=s.approver_id WHERE request_id=? ORDER BY step",
            id));
    return r;
  }

  @PostMapping("/requests/{id}/decision")
  Object decision(
      @PathVariable long id,
      @Valid @RequestBody WorkflowService.Decision in,
      HttpServletRequest req) {
    workflow.decide(id, in, Auth.actor(req));
    return Map.of("message", "审批已处理");
  }

  @PostMapping("/requests/{id}/cancel")
  Object cancel(@PathVariable long id, HttpServletRequest req) {
    workflow.cancel(id, Auth.actor(req));
    return Map.of("message", "已撤回或撤销");
  }

  @GetMapping("/notifications")
  Object notifications(HttpServletRequest req) {
    return db.queryForList(
        "SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 200",
        Auth.actor(req).id());
  }

  @PostMapping("/notifications/{id}/read")
  Object read(@PathVariable long id, HttpServletRequest req) {
    db.update(
        "UPDATE notifications SET read_at=current_timestamp WHERE id=? AND user_id=?",
        id,
        Auth.actor(req).id());
    return Map.of("message", "已读");
  }
}
