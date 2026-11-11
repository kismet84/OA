package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/documents")
class DocumentController {
  final JdbcTemplate db;
  final Auth auth;
  final AttachmentController storage;
  final DocumentMedia media;

  DocumentController(
      JdbcTemplate db, Auth auth, AttachmentController storage, DocumentMedia media) {
    this.db = db;
    this.auth = auth;
    this.storage = storage;
    this.media = media;
  }

  record Publication(
      @NotBlank @Size(max = 200) String title,
      @Size(max = 5000) String description,
      @NotBlank String category,
      Long departmentId,
      @NotNull @Size(min = 1, max = 10) List<@NotNull UUID> attachmentIds,
      Boolean allowDownload) {}

  static final Set<String> CATEGORIES = Set.of("POLICY", "FILE", "OTHER");
  // Evaluate against current organization, including descendants, for every access.
  static final String SCOPE =
      " (d.department_id IS NULL OR u.department_id IN (WITH RECURSIVE tree AS (SELECT id FROM"
          + " departments WHERE id=d.department_id UNION SELECT x.id FROM departments x JOIN tree t"
          + " ON x.parent_id=t.id) SELECT id FROM tree)) ";

  Set<Long> recipients(long id) {
    return new HashSet<>(
        db.queryForList(
            "SELECT u.id FROM users u CROSS JOIN documents d WHERE d.id=? AND u.enabled AND "
                + SCOPE,
            Long.class,
            id));
  }

  Map<String, Object> authorized(long id, Actor actor) {
    var rows = db.queryForList("SELECT * FROM documents WHERE id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ApiException(404, "文档不存在");
    var d = rows.getFirst();
    if (!actor.admin()
        && (!d.get("status").equals("PUBLISHED") || !recipients(id).contains(actor.id())))
      throw new ApiException(403, "文档已下架或您不在接收范围内");
    return d;
  }

  void validate(Publication in, Actor a) {
    ApiException.require(CATEGORIES.contains(in.category()), "文档分类无效");
    if (in.departmentId() != null)
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM departments WHERE id=?", Long.class, in.departmentId())
              == 1,
          "部门不存在");
    ApiException.require(
        new HashSet<>(in.attachmentIds()).size() == in.attachmentIds().size(), "附件不可重复");
    for (UUID file : in.attachmentIds()) {
      var rows =
          db.queryForList(
              "SELECT * FROM attachments WHERE id=? AND user_id=? FOR UPDATE", file, a.id());
      ApiException.require(!rows.isEmpty(), "附件不存在或不是您上传的文件");
      // Never reclassify existing business attachments: their original authorization remains
      // intact.
      ApiException.require(
          db.queryForObject(
                  "SELECT (SELECT count(*) FROM announcement_attachments WHERE"
                      + " attachment_id=?)+(SELECT count(*) FROM requests WHERE"
                      + " attachment_id=?)+(SELECT count(*) FROM expense_attachments WHERE"
                      + " attachment_id=?)",
                  Long.class,
                  file,
                  file,
                  file)
              == 0,
          "该附件已用于公告或审批，请为文档重新上传");
      media.pages(storage.path(file), (String) rows.getFirst().get("media_type"));
    }
  }

  void bind(long id, List<UUID> files) {
    db.update("DELETE FROM document_attachments WHERE document_id=?", id);
    for (int i = 0; i < files.size(); i++) {
      db.update("INSERT INTO document_attachments VALUES(?,?,?)", id, files.get(i), i);
      db.update("INSERT INTO document_file_history VALUES(?) ON CONFLICT DO NOTHING", files.get(i));
    }
  }

  void notify(long id, Set<Long> users, Actor a) {
    String title = db.queryForObject("SELECT title FROM documents WHERE id=?", String.class, id);
    for (long user : users)
      if (user != a.id())
        db.update(
            "INSERT INTO notifications(user_id,message,document_id) VALUES(?,?,?)",
            user,
            "文档资料已发布：" + title,
            id);
  }

  @PostMapping
  @Transactional
  Object publish(@Valid @RequestBody Publication in, HttpServletRequest req) {
    var a = Auth.actor(req);
    a.requireAdmin();
    db.execute("SELECT pg_advisory_xact_lock(20261111)");
    validate(in, a);
    long id =
        db.queryForObject(
            "INSERT INTO"
                + " documents(title,description,category,department_id,publisher_id,allow_download)"
                + " VALUES(?,?,?,?,?,?) RETURNING id",
            Long.class,
            in.title().trim(),
            in.description(),
            in.category(),
            in.departmentId(),
            a.id(),
            in.allowDownload() == null || in.allowDownload());
    bind(id, in.attachmentIds());
    notify(id, recipients(id), a);
    auth.audit(a.id(), "PUBLISH_DOCUMENT", id);
    return Map.of("id", id);
  }

  @PutMapping("/{id}")
  @Transactional
  Object update(@PathVariable long id, @Valid @RequestBody Publication in, HttpServletRequest req) {
    var a = Auth.actor(req);
    a.requireAdmin();
    db.execute("SELECT pg_advisory_xact_lock(20261111)");
    var old = authorized(id, a);
    var before = recipients(id);
    validate(in, a);
    db.update(
        "UPDATE documents SET"
            + " title=?,description=?,category=?,department_id=?,allow_download=?,updated_at=localtimestamp"
            + " WHERE id=?",
        in.title().trim(),
        in.description(),
        in.category(),
        in.departmentId(),
        in.allowDownload() == null || in.allowDownload(),
        id);
    bind(id, in.attachmentIds());
    if (old.get("status").equals("PUBLISHED")) {
      var added = recipients(id);
      added.removeAll(before);
      notify(id, added, a);
    }
    auth.audit(a.id(), "UPDATE_DOCUMENT", id);
    return Map.of("id", id);
  }

  @PostMapping("/{id}/withdraw")
  @Transactional
  Object withdraw(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    a.requireAdmin();
    authorized(id, a);
    if (db.update(
            "UPDATE documents SET status='WITHDRAWN',updated_at=localtimestamp WHERE id=? AND"
                + " status='PUBLISHED'",
            id)
        > 0) auth.audit(a.id(), "WITHDRAW_DOCUMENT", id);
    return Map.of("message", "文档已下架");
  }

  @PostMapping("/{id}/republish")
  @Transactional
  Object republish(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    a.requireAdmin();
    db.execute("SELECT pg_advisory_xact_lock(20261111)");
    authorized(id, a);
    for (var f : files(id))
      media.pages(storage.path((UUID) f.get("id")), (String) f.get("media_type"));
    if (db.update(
            "UPDATE documents SET"
                + " status='PUBLISHED',published_at=localtimestamp,updated_at=localtimestamp WHERE"
                + " id=? AND status='WITHDRAWN'",
            id)
        > 0) {
      notify(id, recipients(id), a);
      auth.audit(a.id(), "REPUBLISH_DOCUMENT", id);
    }
    return Map.of("message", "文档已上架");
  }

  @GetMapping
  Object list(
      @RequestParam(defaultValue = "false") boolean management,
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(required = false) LocalDate publishedFrom,
      @RequestParam(required = false) LocalDate publishedTo,
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "200") int limit,
      HttpServletRequest req) {
    var a = Auth.actor(req);
    if (management) a.requireAdmin();
    ApiException.require(offset >= 0 && limit > 0 && limit <= 200, "每页须为 1 至 200 条，偏移量不能为负");
    ApiException.require(keyword.length() <= 200, "标题关键字过长");
    ApiException.require(
        publishedFrom == null || publishedTo == null || !publishedFrom.isAfter(publishedTo),
        "发布时间范围无效");
    String sql =
        "SELECT d.*,(SELECT r.read_at FROM document_receipts r WHERE r.document_id=d.id AND"
            + " r.user_id=?) read_at FROM documents d WHERE 1=1";
    List<Object> params = new ArrayList<>();
    params.add(a.id());
    if (!management) {
      sql += " AND d.status='PUBLISHED'";
      if (!a.admin()) {
        sql += " AND EXISTS(SELECT 1 FROM users u WHERE u.id=? AND u.enabled AND " + SCOPE + ")";
        params.add(a.id());
      }
    }
    if (category != null && !category.isBlank()) {
      ApiException.require(CATEGORIES.contains(category), "文档分类无效");
      sql += " AND d.category=?";
      params.add(category);
    }
    if (!keyword.isBlank()) {
      sql += " AND position(lower(?) in lower(d.title))>0";
      params.add(keyword.trim());
    }
    if (publishedFrom != null) {
      sql += " AND d.published_at>=?";
      params.add(publishedFrom.atStartOfDay());
    }
    if (publishedTo != null) {
      sql += " AND d.published_at<?";
      params.add(publishedTo.plusDays(1).atStartOfDay());
    }
    params.add(limit);
    params.add(offset);
    return db.queryForList(
        sql + " ORDER BY d.published_at DESC,d.id DESC LIMIT ? OFFSET ?", params.toArray());
  }

  List<Map<String, Object>> files(long id) {
    return db.queryForList(
        "SELECT f.id,f.original_name,f.media_type FROM document_attachments x JOIN attachments f ON"
            + " f.id=x.attachment_id WHERE x.document_id=? ORDER BY x.position",
        id);
  }

  @GetMapping("/{id}")
  @Transactional
  Object detail(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    var result = new LinkedHashMap<>(authorized(id, a));
    result.put("attachments", files(id));
    return result;
  }

  Map<String, Object> file(long id, UUID file) {
    return files(id).stream()
        .filter(f -> f.get("id").equals(file))
        .findFirst()
        .orElseThrow(() -> new ApiException(403, "附件不属于该文档当前内容"));
  }

  String mark(Actor a) {
    String no = db.queryForObject("SELECT employee_no FROM users WHERE id=?", String.class, a.id());
    return a.name() + " · " + no + " · " + LocalDate.now(ZoneId.of("Asia/Shanghai"));
  }

  @GetMapping("/{id}/attachments/{file}/preview")
  @Transactional
  Object previewInfo(@PathVariable long id, @PathVariable UUID file, HttpServletRequest req) {
    var a = Auth.actor(req);
    authorized(id, a);
    var f = file(id, file);
    return Map.of(
        "pages",
        media.pages(storage.path(file), (String) f.get("media_type")),
        "watermark",
        mark(a));
  }

  @GetMapping("/{id}/attachments/{file}/pages/{page}")
  @Transactional
  ResponseEntity<byte[]> preview(
      @PathVariable long id,
      @PathVariable UUID file,
      @PathVariable int page,
      HttpServletRequest req) {
    var a = Auth.actor(req);
    authorized(id, a);
    var f = file(id, file);
    Path path = storage.path(file);
    int count = media.pages(path, (String) f.get("media_type"));
    ApiException.require(page >= 1 && page <= count, "预览页码无效");
    byte[] bytes = media.preview(path, (String) f.get("media_type"), page, mark(a));
    db.update(
        "INSERT INTO document_receipts(document_id,user_id) VALUES(?,?) ON CONFLICT DO NOTHING",
        id,
        a.id());
    return response(bytes, "image/png", "preview.png", false);
  }

  @GetMapping("/{id}/attachments/{file}/download")
  @Transactional
  ResponseEntity<byte[]> download(
      @PathVariable long id, @PathVariable UUID file, HttpServletRequest req) {
    var a = Auth.actor(req);
    var d = authorized(id, a);
    if (!Boolean.TRUE.equals(d.get("allow_download"))) throw new ApiException(403, "该文档仅允许预览，禁止下载");
    var f = file(id, file);
    Path path = storage.path(file);
    String type = (String) f.get("media_type");
    int count = media.pages(path, type);
    boolean pdf = type.equals("application/pdf");
    return response(
        media.download(path, type, count, mark(a)),
        pdf ? type : "image/png",
        f.get("original_name") + "-水印." + (pdf ? "pdf" : "png"),
        true);
  }

  ResponseEntity<byte[]> response(byte[] bytes, String type, String name, boolean attachment) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(type))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header(
            "Content-Disposition",
            (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(name, java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(bytes);
  }

  @GetMapping("/{id}/receipts")
  @Transactional
  Object receipts(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    a.requireAdmin();
    authorized(id, a);
    var current = recipients(id);
    var people =
        db.queryForList(
            "SELECT u.id user_id,u.name,u.employee_no,d.name department_name,r.read_at FROM users u"
                + " JOIN departments d ON d.id=u.department_id LEFT JOIN document_receipts r ON"
                + " r.user_id=u.id AND r.document_id=? WHERE r.read_at IS NOT NULL OR u.enabled"
                + " ORDER BY u.employee_no",
            id);
    var visible =
        people.stream()
            .filter(p -> current.contains(((Number) p.get("user_id")).longValue()))
            .toList();
    var read = visible.stream().filter(p -> p.get("read_at") != null).toList();
    var unread = visible.stream().filter(p -> p.get("read_at") == null).toList();
    var historical =
        people.stream()
            .filter(
                p ->
                    p.get("read_at") != null
                        && !current.contains(((Number) p.get("user_id")).longValue()))
            .toList();
    return Map.of(
        "readCount",
        read.size(),
        "unreadCount",
        unread.size(),
        "read",
        read,
        "unread",
        unread,
        "historical",
        historical);
  }
}
