package com.company.oa;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Call in the association transaction, in UUID order for batches. All writers share this lock. */
final class AttachmentClaims {
  private AttachmentClaims() {}

  static void check(JdbcTemplate db, UUID file, long uploader, String business, Long target) {
    var rows = db.queryForList("SELECT user_id FROM attachments WHERE id=? FOR NO KEY UPDATE", file);
    boolean own = target != null && switch (business) {
      case "CONTRACT" -> db.queryForObject("SELECT count(*) FROM contract_files WHERE contract_id=? AND attachment_id=?", Long.class, target, file) > 0;
      case "ASSET" -> db.queryForObject("SELECT count(*) FROM assets WHERE id=? AND attachment_id=?", Long.class, target, file) > 0;
      default -> false;
    };
    ApiException.require(!rows.isEmpty() && (own || ((Number) rows.getFirst().get("user_id")).longValue() == uploader), "附件不存在或无权使用");
    // Document history has no document ID: preserve its existing within-document-business reuse.
    var args = new ArrayList<Object>();
    var checks = new ArrayList<String>();
    for (String table : List.of("requests", "expense_attachments", "contract_files", "document_file_history", "announcement_attachments", "assets")) {
      if (table.equals("document_file_history") && business.equals("DOCUMENT")) continue;
      String clause = "SELECT 1 FROM " + table + " WHERE attachment_id=?";
      args.add(file);
      if (target != null && table.equals("contract_files") && business.equals("CONTRACT")) {
        clause += " AND contract_id<>?"; args.add(target);
      }
      if (target != null && table.equals("assets") && business.equals("ASSET")) {
        clause += " AND id<>?"; args.add(target);
      }
      checks.add("EXISTS(" + clause + ")");
    }
    ApiException.require(!Boolean.TRUE.equals(db.queryForObject("SELECT " + String.join(" OR ", checks), Boolean.class, args.toArray())), "该附件已用于其他业务，请重新上传");
  }
}
