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
  final AssetController assets;
  final ContractController contracts;

  MessageController(
      JdbcTemplate db,
      RequestController requests,
      AssetController assets,
      ContractController contracts) {
    this.db = db;
    this.requests = requests;
    this.assets = assets;
    this.contracts = contracts;
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
    contracts.reminders();
    long id = Auth.actor(req).id();
    var out = new LinkedHashMap<String, Object>();
    long approvals =
        db.queryForObject(
            "SELECT count(*) FROM requests r JOIN approval_steps s ON s.request_id=r.id AND"
                + " s.step=r.current_step WHERE r.status='PENDING' AND s.approver_id=?",
            Long.class,
            id);
    long tasks =
        db.queryForObject(
            "SELECT count(*) FROM tasks t JOIN task_assignees a ON a.task_id=t.id WHERE a.user_id=?"
                + " AND t.status IN ('TODO','IN_PROGRESS')",
            Long.class,
            id);
    long asset = assets.pending(Auth.actor(req)), contract = contracts.pending(Auth.actor(req));
    out.put("pending", approvals + tasks + asset + contract);
    out.put("approvalsRequests", approvals);
    out.put("approvalsContracts", contract);
    out.put("approvalsAssets", asset);
    out.put("tasks", tasks);
    out.put(
        "payments",
        Auth.actor(req).admin() || Auth.actor(req).has("FINANCE")
            ? db.queryForObject(
                "SELECT count(*) FROM requests WHERE kind='EXPENSE' AND status='APPROVED' AND"
                    + " payment_status='UNPAID'",
                Long.class)
            : 0L);
    out.put(
        "notifications",
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL",
            Long.class,
            id));
    out.put(
        "announcements",
        db.queryForObject(
            "SELECT count(*) FROM announcement_recipients r JOIN announcements a ON"
                + " a.id=r.announcement_id WHERE r.user_id=? AND r.read_at IS NULL AND"
                + " a.withdrawn_at IS NULL",
            Long.class,
            id));
    return out;
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
          "SELECT * FROM notifications WHERE user_id=?"
              + ("true".equals(req.getParameter("unread")) ? " AND read_at IS NULL" : "")
              + " ORDER BY id DESC LIMIT 200 OFFSET ?",
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
    if (category.equals("pending")) {
      String itemType = req.getParameter("itemType");
      ApiException.require(
          itemType == null || Set.of("REQUEST", "CONTRACT", "ASSET", "TASK").contains(itemType),
          "待办业务无效");
      // JSON keeps every existing approval field while giving tasks their own shape.
      var data =
          db.queryForList(
              "SELECT data::text FROM (SELECT"
                  + " to_jsonb(r)||jsonb_build_object('item_type','REQUEST','user_name',u.name,'employee_no',u.employee_no,'type_name',lt.name,'approver_id',s.approver_id)"
                  + " data,r.created_at,r.id FROM requests r JOIN users u ON u.id=r.user_id LEFT"
                  + " JOIN leave_types lt ON lt.id=r.type_id JOIN approval_steps s ON"
                  + " s.request_id=r.id AND s.step=r.current_step WHERE r.status='PENDING' AND"
                  + " s.approver_id=? UNION ALL SELECT"
                  + " to_jsonb(t)||jsonb_build_object('item_type','TASK','task_id',t.id,'creator_name',u.name,'overdue',t.due_at<localtimestamp)"
                  + " data,t.created_at,t.id FROM tasks t JOIN users u ON u.id=t.creator_id JOIN"
                  + " task_assignees a ON a.task_id=t.id WHERE a.user_id=? AND t.status IN"
                  + " ('TODO','IN_PROGRESS') UNION ALL SELECT"
                  + " to_jsonb(r)||jsonb_build_object('item_type','ASSET','title','资产领用','user_name',u.name)"
                  + " data,r.created_at,r.id FROM asset_requests r JOIN users u ON u.id=r.user_id"
                  + " WHERE "
                  + assets.pendingSql()
                  + " UNION ALL SELECT"
                  + " to_jsonb(c)||jsonb_build_object('item_type','CONTRACT','title',c.name,'user_name',u.name)"
                  + " data,c.created_at,c.id FROM contracts c JOIN users u ON u.id=c.user_id WHERE "
                  + contracts.pendingSql(a)
                  + ") combined"
                  + (itemType != null ? " WHERE data->>'item_type'='" + itemType + "'" : "")
                  + " ORDER BY created_at DESC,id"
                  + " DESC,data->>'item_type' LIMIT 200 OFFSET ?",
              String.class,
              a.id(),
              a.id(),
              a.id(),
              AssetController.manages(a),
              start);
      var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
      return data.stream()
          .map(
              json -> {
                try {
                  return mapper.readValue(json, Map.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                  throw new IllegalStateException(e);
                }
              })
          .toList();
    }
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
        q.sql().replace("SELECT r.*", "SELECT 'REQUEST' item_type,r.*")
            + clause
            + " ORDER BY r.id DESC LIMIT 200 OFFSET ?",
        q.params().toArray());
  }
}
