package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/attachments")
class AttachmentController {
  final JdbcTemplate db;
  final Path root;
  final Auth auth;

  AttachmentController(JdbcTemplate db, Auth auth, @Value("${oa.uploads}") String path)
      throws IOException {
    this.db = db;
    this.auth = auth;
    root = Path.of(path).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  @PostMapping
  @Transactional
  Object upload(@RequestParam MultipartFile file, HttpServletRequest req) throws IOException {
    byte[] bytes = file.getBytes();
    ApiException.require(bytes.length > 0, "附件不能为空");
    String type;
    if (bytes.length > 4
        && bytes[0] == '%'
        && bytes[1] == 'P'
        && bytes[2] == 'D'
        && bytes[3] == 'F') type = "application/pdf";
    else if (bytes.length > 8
        && Arrays.equals(
            Arrays.copyOf(bytes, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      type = "image/png";
    else if (bytes.length > 3
        && bytes[0] == (byte) 255
        && bytes[1] == (byte) 216
        && bytes[2] == (byte) 255) type = "image/jpeg";
    else throw new ApiException(400, "仅支持 PDF、PNG、JPEG 证明文件");
    UUID id = UUID.randomUUID();
    String name = Optional.ofNullable(file.getOriginalFilename()).orElse("证明文件");
    name = name.replaceAll("[\\r\\n\\\\/]", "_");
    if (name.length() > 200) name = name.substring(name.length() - 200);
    Files.write(root.resolve(id.toString()), bytes, StandardOpenOption.CREATE_NEW);
    db.update(
        "INSERT INTO attachments(id,user_id,original_name,media_type) VALUES(?,?,?,?)",
        id,
        Auth.actor(req).id(),
        name,
        type);
    auth.audit(Auth.actor(req).id(), "UPLOAD_ATTACHMENT", id);
    return Map.of("id", id, "name", name);
  }

  @GetMapping("/{id}")
  ResponseEntity<Resource> download(@PathVariable UUID id, HttpServletRequest req) {
    var rows = db.queryForList("SELECT * FROM attachments WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, "附件不存在");
    var file = rows.getFirst();
    Actor a = Auth.actor(req);
    if (!a.admin()
        && ((Number) file.get("user_id")).longValue() != a.id()
        && db.queryForObject(
                "SELECT count(*) FROM requests r WHERE (r.attachment_id=? OR EXISTS(SELECT 1 FROM expense_attachments e WHERE e.request_id=r.id AND e.attachment_id=?)) AND ((r.kind='EXPENSE' AND r.status='APPROVED' AND ?) OR r.user_id=? OR"
                    + " EXISTS(SELECT 1 FROM approval_steps s WHERE s.request_id=r.id AND"
                    + " s.approver_id=?))",
                Long.class,
                id,
                id,
                a.has("FINANCE"),
                a.id(),
                a.id())
            == 0) throw new ApiException(403, "无权访问该附件");
    Path path = root.resolve(id.toString());
    if (!Files.isRegularFile(path)) throw new ApiException(404, "附件文件缺失，请联系管理员");
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename(
                    (String) file.get("original_name"), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(new FileSystemResource(path));
  }
}
