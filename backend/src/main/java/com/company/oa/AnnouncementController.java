package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/announcements")
class AnnouncementController {
  final JdbcTemplate db;
  final Auth auth;

  AnnouncementController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  record Publication(
      @NotBlank @Size(max = 200) String title,
      @NotBlank @Size(max = 50000) String body,
      Long departmentId,
      @Size(max = 30) List<@NotNull UUID> attachmentIds) {}

  @PostMapping
  @Transactional
  Object publish(@Valid @RequestBody Publication in, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    a.requireAdmin();
    // Serialize with organization updates and application submission for a coherent recipient
    // snapshot.
    ApprovalSupport.organizationSnapshot(db);
    if (in.departmentId() != null)
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM departments WHERE id=?", Long.class, in.departmentId())
              == 1,
          "部门不存在");
    var files = in.attachmentIds() == null ? List.<UUID>of() : in.attachmentIds();
    ApiException.require(new HashSet<>(files).size() == files.size(), "附件不可重复");
    for (UUID file : files)
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM attachments WHERE id=? AND user_id=? AND NOT EXISTS(SELECT 1 FROM document_file_history h WHERE h.attachment_id=attachments.id)",
                  Long.class,
                  file,
                  a.id())
              == 1,
          "附件不存在或无权使用");
    long id =
        db.queryForObject(
            "INSERT INTO announcements(title,body,department_id,publisher_id) VALUES(?,?,?,?)"
                + " RETURNING id",
            Long.class,
            in.title().trim(),
            in.body(),
            in.departmentId(),
            a.id());
    String scope =
        in.departmentId() == null
            ? ""
            : " AND u.department_id IN (WITH RECURSIVE tree AS (SELECT id FROM departments WHERE"
                  + " id=? UNION ALL SELECT d.id FROM departments d JOIN tree t ON"
                  + " d.parent_id=t.id) SELECT id FROM tree)";
    List<Object> params = new ArrayList<>();
    params.add(id);
    if (in.departmentId() != null) params.add(in.departmentId());
    db.update(
        "INSERT INTO"
            + " announcement_recipients(announcement_id,user_id,name,employee_no,department_name)"
            + " SELECT ?,u.id,u.name,u.employee_no,d.name FROM users u JOIN departments d ON"
            + " d.id=u.department_id WHERE u.enabled"
            + scope,
        params.toArray());
    for (UUID file : files) db.update("INSERT INTO announcement_attachments VALUES(?,?)", id, file);
    auth.audit(a.id(), "PUBLISH_ANNOUNCEMENT", id);
    return Map.of("id", id);
  }

  @GetMapping
  Object list(
      @RequestParam(defaultValue = "false") boolean management,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    Actor a = Auth.actor(req);
    if (management) a.requireAdmin();
    if (management)
      return db.queryForList(
          "SELECT a.id,a.title,a.department_id,a.published_at,a.withdrawn_at,(SELECT count(*) FROM"
              + " announcement_recipients r WHERE r.announcement_id=a.id AND r.read_at IS NOT NULL)"
              + " read_count,(SELECT count(*) FROM announcement_recipients r WHERE"
              + " r.announcement_id=a.id AND r.read_at IS NULL) unread_count FROM announcements a"
              + " ORDER BY a.id DESC LIMIT 200 OFFSET ?",
          Math.max(0, offset));
    return db.queryForList(
        "SELECT a.id,a.title,a.published_at,r.read_at FROM announcements a JOIN"
            + " announcement_recipients r ON r.announcement_id=a.id WHERE r.user_id=? AND"
            + " a.withdrawn_at IS NULL ORDER BY a.id DESC LIMIT 200 OFFSET ?",
        a.id(),
        Math.max(0, offset));
  }

  Map<String, Object> authorized(long id, Actor a) {
    var rows = db.queryForList("SELECT * FROM announcements WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ApiException(404, "公告不存在");
    var row = rows.getFirst();
    if (!a.admin()
        && (row.get("withdrawn_at") != null
            || db.queryForObject(
                    "SELECT count(*) FROM announcement_recipients WHERE announcement_id=? AND"
                        + " user_id=?",
                    Long.class,
                    id,
                    a.id())
                == 0)) throw new ApiException(403, "无权查看该公告");
    return row;
  }

  @GetMapping("/{id}")
  @Transactional
  Object detail(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    var result = new LinkedHashMap<>(authorized(id, a));
    db.update(
        "UPDATE announcement_recipients SET read_at=coalesce(read_at,current_timestamp) WHERE"
            + " announcement_id=? AND user_id=? AND EXISTS(SELECT 1 FROM announcements WHERE id=?"
            + " AND withdrawn_at IS NULL)",
        id,
        a.id(),
        id);
    result.put(
        "read_at",
        db
            .queryForList(
                "SELECT read_at FROM announcement_recipients WHERE announcement_id=? AND user_id=?",
                id,
                a.id())
            .stream()
            .findFirst()
            .map(r -> r.get("read_at"))
            .orElse(null));
    result.put(
        "attachments",
        db.queryForList(
            "SELECT f.id,f.original_name FROM announcement_attachments x JOIN attachments f ON"
                + " f.id=x.attachment_id WHERE x.announcement_id=? ORDER BY f.created_at,f.id",
            id));
    return result;
  }

  @PostMapping("/{id}/read")
  @Transactional
  Object read(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    var row = authorized(id, a);
    ApiException.require(row.get("withdrawn_at") == null, "公告已撤回");
    if (db.update(
            "UPDATE announcement_recipients SET read_at=coalesce(read_at,current_timestamp) WHERE"
                + " announcement_id=? AND user_id=?",
            id,
            a.id())
        == 0) throw new ApiException(403, "您不在接收人名单中");
    return Map.of("message", "已读");
  }

  @GetMapping("/{id}/receipts")
  @Transactional
  Object receipts(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    a.requireAdmin();
    authorized(id, a);
    var people =
        db.queryForList(
            "SELECT user_id,name,employee_no,department_name,read_at FROM announcement_recipients"
                + " WHERE announcement_id=? ORDER BY employee_no",
            id);
    var read = people.stream().filter(p -> p.get("read_at") != null).toList();
    var unread = people.stream().filter(p -> p.get("read_at") == null).toList();
    return Map.of(
        "readCount", read.size(), "unreadCount", unread.size(), "read", read, "unread", unread);
  }

  @PostMapping("/{id}/withdraw")
  @Transactional
  Object withdraw(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    a.requireAdmin();
    authorized(id, a);
    if (db.update(
            "UPDATE announcements SET withdrawn_at=current_timestamp WHERE id=? AND withdrawn_at IS"
                + " NULL",
            id)
        > 0) auth.audit(a.id(), "WITHDRAW_ANNOUNCEMENT", id);
    return Map.of("message", "公告已撤回");
  }
}
