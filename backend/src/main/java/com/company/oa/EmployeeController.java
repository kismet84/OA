package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class EmployeeController {
  final JdbcTemplate db;
  final Auth auth;

  EmployeeController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  @GetMapping("/directory/departments")
  Object departments() {
    return db.queryForList("SELECT id,name,parent_id FROM departments ORDER BY id");
  }

  @GetMapping("/directory")
  Object directory(
      @RequestParam(defaultValue = "") @Size(max = 100) String search,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(defaultValue = "0") int offset) {
    ApiException.require(search.length() <= 100, "搜索最多100字");
    String term = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return db.queryForList(
        "WITH RECURSIVE tree AS (SELECT id FROM departments WHERE id=? UNION ALL SELECT d.id FROM"
            + " departments d JOIN tree t ON d.parent_id=t.id) SELECT"
            + " u.id,u.name,u.employee_no,u.department_id,d.name"
            + " department_name,u.job_title,u.work_phone FROM users u JOIN departments d ON"
            + " d.id=u.department_id WHERE u.enabled AND (?::bigint IS NULL OR u.department_id IN"
            + " (SELECT id FROM tree)) AND (u.name ILIKE ? OR u.employee_no ILIKE ? OR d.name ILIKE"
            + " ?) ORDER BY u.employee_no LIMIT 200 OFFSET ?",
        departmentId,
        departmentId,
        term,
        term,
        term,
        Math.max(0, offset));
  }

  @GetMapping("/profile")
  Object profile(HttpServletRequest req) {
    return db.queryForMap(
        "SELECT u.id,u.employee_no,u.name,u.roles,u.department_id,d.name"
            + " department_name,u.job_title,u.work_phone FROM users u JOIN departments d ON"
            + " d.id=u.department_id WHERE u.id=?",
        Auth.actor(req).id());
  }

  record Phone(@Size(max = 40) @Pattern(regexp = "[0-9+() -]{0,40}") String workPhone) {}

  @PutMapping("/profile/work-phone")
  @Transactional
  Object phone(@Valid @RequestBody Phone in, HttpServletRequest req) {
    long id = Auth.actor(req).id();
    db.update("UPDATE users SET work_phone=? WHERE id=?", in.workPhone(), id);
    auth.audit(id, "CHANGE_WORK_PHONE", id);
    return Map.of("message", "工作电话已更新");
  }

  Object sessions(long user, HttpServletRequest req) {
    return db
        .queryForList(
            "SELECT s.id,s.device_name,s.platform,s.created_at,s.expires_at,(s.id=?) AS current"
                + " FROM login_sessions s JOIN users u ON u.id=s.user_id WHERE s.user_id=? AND"
                + " u.enabled AND s.session_version=u.session_version AND s.revoked_at IS NULL AND"
                + " s.expires_at>now() ORDER BY s.created_at DESC",
            Auth.sessionId(Auth.token(req)),
            user)
        .stream()
        .filter(row -> Boolean.TRUE.equals(auth.redis.hasKey("session-id:" + row.get("id"))))
        .toList();
  }

  @GetMapping("/auth/sessions")
  Object mine(HttpServletRequest req) {
    return sessions(Auth.actor(req).id(), req);
  }

  @GetMapping("/admin/users/{user}/sessions")
  Object employeeSessions(@PathVariable long user, HttpServletRequest req) {
    Auth.actor(req).requireAdmin();
    return sessions(user, req);
  }

  Object revoke(long user, String id, HttpServletRequest req) {
    int count;
    if (id.equals("others"))
      count =
          db.update(
              "UPDATE login_sessions SET revoked_at=now() WHERE user_id=? AND id<>? AND revoked_at"
                  + " IS NULL",
              user,
              Auth.sessionId(Auth.token(req)));
    else
      count =
          db.update(
              "UPDATE login_sessions SET revoked_at=now() WHERE user_id=? AND id=? AND revoked_at"
                  + " IS NULL",
              user,
              id);
    if (!id.equals("others") && count == 0) throw new ApiException(404, "设备不存在或已退出");
    auth.audit(Auth.actor(req).id(), "REVOKE_SESSION", user + ":" + id);
    return Map.of("message", "设备已退出");
  }

  @DeleteMapping("/auth/sessions/{id}")
  @Transactional
  Object revokeMine(@PathVariable String id, HttpServletRequest req) {
    return revoke(Auth.actor(req).id(), id, req);
  }

  @DeleteMapping("/admin/users/{user}/sessions/{id}")
  @Transactional
  Object revokeEmployee(@PathVariable long user, @PathVariable String id, HttpServletRequest req) {
    Auth.actor(req).requireAdmin();
    return revoke(user, id, req);
  }
}
