package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
class TaskController {
  final JdbcTemplate db;
  final Auth auth;
  static final Set<String> STATES = Set.of("TODO", "IN_PROGRESS", "COMPLETED", "CANCELLED");

  TaskController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    var result = db.queryForList(sql, args);
    result.forEach(r -> r.replaceAll((k, v) -> v instanceof Timestamp t ? t.toLocalDateTime() : v));
    return result;
  }

  Set<Long> assignees(long id) {
    return new LinkedHashSet<>(
        db.queryForList("SELECT user_id FROM task_assignees WHERE task_id=?", Long.class, id));
  }

  void lock(long id) {
    db.queryForList("SELECT id FROM tasks WHERE id=? FOR UPDATE", id);
  }

  Map<String, Object> task(long id, Actor a) {
    var list =
        rows(
            "SELECT t.*,u.name creator_name,(t.due_at<localtimestamp AND t.status IN"
                + " ('TODO','IN_PROGRESS')) overdue FROM tasks t JOIN users u ON u.id=t.creator_id"
                + " WHERE t.id=?",
            id);
    if (list.isEmpty()) throw new ApiException(404, "任务不存在");
    var t = list.getFirst();
    if (!a.admin()
        && !Objects.equals(((Number) t.get("creator_id")).longValue(), a.id())
        && !assignees(id).contains(a.id())) throw new ApiException(403, "无权查看或操作该任务");
    return t;
  }

  boolean owner(Map<String, Object> t, Actor a) {
    return a.admin() || ((Number) t.get("creator_id")).longValue() == a.id();
  }

  void requireOwner(Map<String, Object> t, Actor a) {
    if (!owner(t, a)) throw new ApiException(403, "仅发起人或管理员可执行此操作");
  }

  @GetMapping
  Object list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) LocalDate dueFrom,
      @RequestParam(required = false) LocalDate dueTo,
      @RequestParam(required = false) Long creatorId,
      @RequestParam(required = false) Long assigneeId,
      @RequestParam(defaultValue = "") String scope,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    Actor a = Auth.actor(req);
    ApiException.require(status == null || status.isEmpty() || STATES.contains(status), "任务状态无效");
    ApiException.require(Set.of("", "assigned", "initiated").contains(scope), "任务范围无效");
    ApiException.require(dueFrom == null || dueTo == null || !dueFrom.isAfter(dueTo), "截止日期范围无效");
    var sql =
        new StringBuilder(
            "SELECT t.*,u.name creator_name,(t.due_at<localtimestamp AND t.status IN"
                + " ('TODO','IN_PROGRESS')) overdue FROM tasks t JOIN users u ON u.id=t.creator_id"
                + " WHERE 1=1");
    var args = new ArrayList<Object>();
    if (!a.admin()) {
      sql.append(
          " AND (t.creator_id=? OR EXISTS(SELECT 1 FROM task_assignees a WHERE a.task_id=t.id AND"
              + " a.user_id=?))");
      args.add(a.id());
      args.add(a.id());
    }
    if (scope.equals("assigned")) assigneeId = a.id();
    if (scope.equals("initiated")) {
      creatorId = a.id();
      sql.append(" AND t.status<>'CANCELLED'");
    }
    if (status != null && !status.isEmpty()) {
      sql.append(" AND t.status=?");
      args.add(status);
    }
    if (creatorId != null) {
      sql.append(" AND t.creator_id=?");
      args.add(creatorId);
    }
    if (assigneeId != null) {
      sql.append(
          " AND EXISTS(SELECT 1 FROM task_assignees a WHERE a.task_id=t.id AND a.user_id=?)");
      args.add(assigneeId);
    }
    if (dueFrom != null) {
      sql.append(" AND t.due_at>=?");
      args.add(dueFrom.atStartOfDay());
    }
    if (dueTo != null) {
      sql.append(" AND t.due_at<?");
      args.add(dueTo.plusDays(1).atStartOfDay());
    }
    sql.append(" ORDER BY t.created_at DESC,t.id DESC LIMIT 200 OFFSET ?");
    args.add(Math.max(0, offset));
    return rows(sql.toString(), args.toArray());
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    var t = task(id, a);
    t.put("canEdit", owner(t, a));
    t.put("canRestore", a.admin() || (owner(t, a) && t.get("status").equals("COMPLETED")));
    t.put(
        "assignees",
        rows(
            "SELECT a.user_id,u.name,u.employee_no FROM task_assignees a JOIN users u ON"
                + " u.id=a.user_id WHERE task_id=? ORDER BY u.employee_no",
            id));
    t.put(
        "followups",
        rows(
            "SELECT f.*,u.name user_name FROM task_followups f JOIN users u ON u.id=f.user_id WHERE"
                + " task_id=? ORDER BY f.created_at,f.id",
            id));
    return t;
  }

  record Input(
      @NotBlank @Size(max = 200) String title,
      @Size(max = 5000) String description,
      String dueAt,
      @NotNull @Size(min = 1) List<@NotNull Long> assigneeIds) {}

  static LocalDateTime due(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return value.length() == 10
          ? LocalDate.parse(value).atTime(23, 59, 59)
          : LocalDateTime.parse(value);
    } catch (Exception e) {
      throw new ApiException(400, "截止日期须为上海日期或日期时间");
    }
  }

  @PostMapping
  @Transactional
  Object create(@Valid @RequestBody Input in, HttpServletRequest req) {
    return save(null, in, Auth.actor(req));
  }

  @PutMapping("/{id}")
  @Transactional
  Object update(@PathVariable long id, @Valid @RequestBody Input in, HttpServletRequest req) {
    return save(id, in, Auth.actor(req));
  }

  Object save(Long id, Input in, Actor a) {
    if (id != null) lock(id);
    var old = id == null ? null : task(id, a);
    if (old != null) {
      requireOwner(old, a);
      ApiException.require(!old.get("status").equals("CANCELLED"), "已取消任务不可修改");
    }
    var people = new LinkedHashSet<>(in.assigneeIds());
    var previous = id == null ? Set.<Long>of() : assignees(id);
    ApiException.require(people.size() <= 200, "负责人最多200人");
    var deadline = due(in.dueAt());
    if (old != null && old.get("status").equals("COMPLETED"))
      ApiException.require(
          people.equals(previous) && Objects.equals(deadline, old.get("due_at")),
          "已完成任务不可改负责人或截止日期，请先重开");
    for (long p : people)
      ApiException.require(
          db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, p)
              == 1,
          "负责人必须为在职员工");
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO tasks(creator_id,title,description,due_at) VALUES(?,?,?,?) RETURNING id",
              Long.class,
              a.id(),
              in.title().trim(),
              in.description(),
              deadline);
    else
      db.update(
          "UPDATE tasks SET title=?,description=?,due_at=?,updated_at=localtimestamp WHERE id=?",
          in.title().trim(),
          in.description(),
          deadline,
          id);
    for (long p : previous)
      if (!people.contains(p)) {
        db.update("DELETE FROM task_assignees WHERE task_id=? AND user_id=?", id, p);
        notify(p, a.id(), id, "你已被移出任务：" + in.title());
      }
    for (long p : people)
      if (!previous.contains(p)) {
        db.update("INSERT INTO task_assignees VALUES(?,?)", id, p);
        // Creating a self-assigned task still produces exactly one assignment notice.
        if (old == null)
          db.update(
              "INSERT INTO notifications(user_id,message,task_id) VALUES(?,?,?)",
              p,
              "任务指派：" + in.title(),
              id);
        else notify(p, a.id(), id, "任务指派：" + in.title());
      }
    if (old != null) {
      var affected = new LinkedHashSet<>(people);
      affected.retainAll(previous);
      long creator = ((Number) old.get("creator_id")).longValue();
      // A creator added/removed by an administrator already received the assignment notice.
      if (people.contains(creator) == previous.contains(creator)) affected.add(creator);
      for (long p : affected) notify(p, a.id(), id, "任务已修改：" + in.title());
    }
    auth.audit(a.id(), old == null ? "CREATE_TASK" : "UPDATE_TASK", id);
    return Map.of("id", id);
  }

  void notify(long user, long actor, long id, String text) {
    if (user != actor)
      db.update("INSERT INTO notifications(user_id,message,task_id) VALUES(?,?,?)", user, text, id);
  }

  void notifyAll(long id, Map<String, Object> t, Actor a, String text) {
    var people = assignees(id);
    people.add(((Number) t.get("creator_id")).longValue());
    for (long p : people) notify(p, a.id(), id, text + "：" + t.get("title"));
  }

  record Followup(@NotBlank @Size(max = 5000) String content) {}

  @PostMapping("/{id}/followups")
  @Transactional
  Object follow(@PathVariable long id, @Valid @RequestBody Followup in, HttpServletRequest req) {
    lock(id);
    Actor a = Auth.actor(req);
    var t = task(id, a);
    ApiException.require(!t.get("status").equals("CANCELLED"), "已取消任务不可跟进，请先恢复");
    db.update(
        "INSERT INTO task_followups(task_id,user_id,content) VALUES(?,?,?)",
        id,
        a.id(),
        in.content().trim());
    db.update("UPDATE tasks SET updated_at=localtimestamp WHERE id=?", id);
    notifyAll(id, t, a, "任务有新跟进");
    auth.audit(a.id(), "FOLLOW_TASK", id);
    return Map.of("message", "跟进已追加");
  }

  record State(@NotNull String status, @Size(max = 5000) String content) {}

  @PostMapping("/{id}/status")
  @Transactional
  Object state(@PathVariable long id, @Valid @RequestBody State in, HttpServletRequest req) {
    lock(id);
    Actor a = Auth.actor(req);
    var t = task(id, a);
    String old = (String) t.get("status"), next = in.status();
    ApiException.require(STATES.contains(next), "任务状态无效");
    String action = "STATUS_TASK", text = "任务状态已变更";
    if (old.equals("CANCELLED")) {
      if (!a.admin()) throw new ApiException(403, "仅管理员可恢复已取消任务");
      ApiException.require(next.equals("TODO"), "已取消任务仅可恢复为待处理");
      action = "RESTORE_TASK";
      text = "任务已恢复";
    } else if (old.equals("COMPLETED")) {
      requireOwner(t, a);
      ApiException.require(
          next.equals("IN_PROGRESS") || next.equals("CANCELLED"), "已完成任务仅可重开为进行中或取消");
      action = next.equals("CANCELLED") ? "CANCEL_TASK" : "RESTORE_TASK";
      text = next.equals("CANCELLED") ? "任务已取消" : "任务已重开";
    } else if (next.equals("CANCELLED")) {
      requireOwner(t, a);
      action = "CANCEL_TASK";
      text = "任务已取消";
    } else if (next.equals("COMPLETED")) {
      action = "COMPLETE_TASK";
      text = "任务已完成";
    }
    ApiException.require(!old.equals(next), "任务已是该状态");
    if (in.content() != null && !in.content().isBlank())
      db.update(
          "INSERT INTO task_followups(task_id,user_id,content) VALUES(?,?,?)",
          id,
          a.id(),
          in.content().trim());
    db.update("UPDATE tasks SET status=?,updated_at=localtimestamp WHERE id=?", next, id);
    notifyAll(id, t, a, text);
    auth.audit(a.id(), action, id);
    return Map.of("message", text);
  }
}
