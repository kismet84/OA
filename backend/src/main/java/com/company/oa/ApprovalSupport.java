package com.company.oa;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared assignment validation and immutable audit history. Call inside the business transaction.
 */
final class ApprovalSupport {
  record Reassign(@NotNull Long approverId, @NotBlank @Size(max = 1000) String note) {}

  static void organizationSnapshot(JdbcTemplate db) {
    db.execute("SELECT pg_advisory_xact_lock_shared(20261114)");
  }

  static void reassign(
      JdbcTemplate db,
      Actor actor,
      String business,
      long id,
      int revision,
      int step,
      Long previous,
      long owner,
      Reassign in) {
    actor.requireAdmin();
    ApiException.require(in.approverId() != owner, "不能改派给申请人本人");
    ApiException.require(!in.approverId().equals(previous), "该员工已是当前审批人");
    ApiException.require(
        db.queryForObject(
                "SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, in.approverId())
            == 1,
        "新审批人不存在或已停用，请选择在职有效员工");
    db.update(
        "INSERT INTO"
            + " approval_reassignments(business,target_id,revision,step,previous_approver_id,approver_id,actor_id,note)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        business,
        id,
        revision,
        step,
        previous,
        in.approverId(),
        actor.id(),
        in.note());
  }

  static String proxyNote(String note) {
    return "管理员代批：" + (note == null ? "" : note);
  }
}
