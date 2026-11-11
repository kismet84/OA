package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class MessageController {
  final JdbcTemplate db;
  final RequestController requests;

  MessageController(JdbcTemplate db, RequestController requests) {
    this.db = db;
    this.requests = requests;
  }

  @GetMapping("/colleagues")
  Object colleagues(HttpServletRequest req) {
    return db.queryForList(
        "SELECT u.id,u.name,u.employee_no,d.name department_name FROM users u JOIN departments d ON"
            + " d.id=u.department_id WHERE u.enabled AND u.id<>? ORDER BY u.employee_no",
        Auth.actor(req).id());
  }

  @GetMapping("/messages/summary")
  Object summary(HttpServletRequest req) {
    long id = Auth.actor(req).id();
    return Map.of(
        "pending",
            db.queryForObject(
                "SELECT count(*) FROM requests r JOIN approval_steps s ON s.request_id=r.id AND"
                    + " s.step=r.current_step WHERE r.status='PENDING' AND s.approver_id=?",
                Long.class,
                id),
        "notifications",
            db.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL",
                Long.class,
                id),
        "announcements",
            db.queryForObject(
                "SELECT count(*) FROM announcement_recipients r JOIN announcements a ON"
                    + " a.id=r.announcement_id WHERE r.user_id=? AND r.read_at IS NULL AND"
                    + " a.withdrawn_at IS NULL",
                Long.class,
                id));
  }

  @GetMapping("/messages")
  Object list(
      @RequestParam String category,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    Actor a = Auth.actor(req);
    int start = Math.max(0, offset);
    if (category.equals("notifications"))
      return db.queryForList(
          "SELECT * FROM notifications WHERE user_id=? ORDER BY id DESC LIMIT 200 OFFSET ?",
          a.id(),
          start);
    if (category.equals("announcements"))
      return db.queryForList(
          "SELECT a.id,a.title,a.published_at,r.read_at FROM announcements a JOIN"
              + " announcement_recipients r ON r.announcement_id=a.id WHERE r.user_id=? AND"
              + " a.withdrawn_at IS NULL ORDER BY a.id DESC LIMIT 200 OFFSET ?",
          a.id(),
          start);
    ApiException.require(Set.of("pending", "initiated", "cc").contains(category), "消息类别无效");
    var q = requests.requestQuery(a, "", "", null, category.equals("pending"));
    String clause =
        category.equals("initiated")
            ? " AND r.user_id=?"
            : category.equals("cc")
                ? " AND EXISTS(SELECT 1 FROM request_cc c WHERE c.request_id=r.id AND c.user_id=?)"
                : "";
    if (!clause.isEmpty()) q.params().add(a.id());
    q.params().add(start);
    return db.queryForList(
        q.sql() + clause + " ORDER BY r.id DESC LIMIT 200 OFFSET ?", q.params().toArray());
  }
}
