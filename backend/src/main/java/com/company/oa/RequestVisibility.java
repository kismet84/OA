package com.company.oa;

import java.util.List;

/** Shared row visibility; callers use the requests alias r. */
final class RequestVisibility {
  private RequestVisibility() {}

  static String sql(Actor a, List<Object> params) {
    if (a.admin()) return "TRUE";
    params.add(a.has("FINANCE"));
    for (int i = 0; i < 6; i++) params.add(a.id());
    return """
        ((r.kind='EXPENSE' AND r.status='APPROVED' AND ?) OR r.user_id=?
        OR EXISTS(SELECT 1 FROM approval_steps s WHERE s.request_id=r.id AND (s.approver_id=? OR s.actor_id=?))
        OR EXISTS(SELECT 1 FROM approval_reassignments h WHERE h.business='REQUEST' AND h.target_id=r.id
          AND (h.previous_approver_id=? OR h.approver_id=?))
        OR EXISTS(SELECT 1 FROM request_cc c WHERE c.request_id=r.id AND c.user_id=?))
        """;
  }
}
