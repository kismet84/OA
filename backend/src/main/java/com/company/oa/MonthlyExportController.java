package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/exports")
class MonthlyExportController {
  final AttendanceController attendance;
  final AttendanceService service;
  final JdbcTemplate db;
  final Auth auth;

  MonthlyExportController(AttendanceController a, AttendanceService s, JdbcTemplate db, Auth auth) {
    attendance = a;
    service = s;
    this.db = db;
    this.auth = auth;
  }

  ResponseEntity<String> csv(
      String filename, List<String> headers, List<List<Object>> rows, Actor a, String action) {
    ApiException.require(rows.size() <= 10000, "导出超过 10000 条，请缩小筛选范围");
    var out = new StringBuilder("\ufeff");
    out.append(String.join(",", headers.stream().map(ExportController::cell).toList()))
        .append("\r\n");
    for (var row : rows)
      out.append(String.join(",", row.stream().map(ExportController::cell).toList()))
          .append("\r\n");
    auth.audit(a.id(), action, rows.size());
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header("Content-Disposition", "attachment; filename=\"" + filename + ".csv\"")
        .body(out.toString());
  }

  @GetMapping("/attendance-monthly.csv")
  Object attendance(
      @RequestParam String month,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(defaultValue = "false") boolean detail,
      HttpServletRequest r) {
    var a = Auth.actor(r);
    a.requireAdmin();
    var m = AttendanceService.month(month);
    var rows = new ArrayList<List<Object>>();
    for (long id : attendance.authorized(a, departmentId, null)) {
      var s = service.summary(id, m.atDay(1), m.atEndOfMonth());
      if (detail) {
        for (var d : service.daily(id, m.atDay(1), m.atEndOfMonth())) {
          var row = new ArrayList<Object>();
          for (String k : List.of("employee_no", "user_name", "department_name")) row.add(s.get(k));
          for (String k :
              List.of(
                  "day",
                  "status",
                  "firstPunch",
                  "lastPunch",
                  "lateMinutes",
                  "earlyMinutes",
                  "missingPunch",
                  "requestIds"))
            row.add(
                k.equals("status")
                    ? STATUS.getOrDefault(d.get(k).toString(), d.get(k).toString())
                    : d.get(k));
          rows.add(row);
        }
      } else {
        var row = new ArrayList<Object>();
        for (String k :
            List.of(
                "employee_no",
                "user_name",
                "department_name",
                "expectedDays",
                "normalDays",
                "lateCount",
                "lateMinutes",
                "earlyCount",
                "earlyMinutes",
                "missingCount",
                "leaveDays",
                "fieldDays")) row.add(s.get(k));
        rows.add(row);
      }
      ApiException.require(rows.size() <= 10000, "导出超过 10000 条，请缩小筛选范围");
    }
    return csv(
        "attendance-" + month,
        detail
            ? List.of("工号", "姓名", "部门", "日期", "状态", "首次打卡", "末次打卡", "迟到分钟", "早退分钟", "缺卡说明", "关联单号")
            : List.of(
                "工号", "姓名", "部门", "应出勤", "正常", "迟到次数", "迟到分钟", "早退次数", "早退分钟", "缺卡次数", "请假天数",
                "外勤天数"),
        rows,
        a,
        "EXPORT_ATTENDANCE_MONTHLY");
  }

  static final Map<String, String> STATUS =
      Map.ofEntries(
          Map.entry("NORMAL", "正常"),
          Map.entry("LATE", "迟到"),
          Map.entry("EARLY", "早退"),
          Map.entry("LATE_EARLY", "迟到及早退"),
          Map.entry("MISSING", "缺卡"),
          Map.entry("LEAVE", "请假"),
          Map.entry("FIELD", "外勤出勤"),
          Map.entry("REST", "休息日"),
          Map.entry("EXCLUDED", "未纳入统计"),
          Map.entry("FUTURE", "未来日期"),
          Map.entry("IN_PROGRESS", "进行中"),
          Map.entry("RECORDED", "无打卡记录（规则关闭）"),
          Map.entry("PUNCHED", "已打卡（规则关闭）"));

  @GetMapping("/expenses-monthly.csv")
  Object expenses(
      @RequestParam String month,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "created") String basis,
      HttpServletRequest r) {
    var a = Auth.actor(r);
    if (!a.admin() && !a.has("FINANCE")) throw new ApiException(403, "仅管理员或财务可导出报销汇总");
    ApiException.require(
        Set.of("created", "approved").contains(basis), "月份口径须为 created 或 approved");
    ApiException.require(
        status.isEmpty()
            || Set.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN", "CANCELLED").contains(status),
        "审批状态无效");
    var m = AttendanceService.month(month);
    var allowed = attendance.authorized(a, departmentId, null);
    String ids =
        allowed.isEmpty()
            ? "NULL"
            : String.join(",", allowed.stream().map(String::valueOf).toList());
    String at =
        basis.equals("approved")
            ? "(SELECT max(s.acted_at) FROM approval_steps s WHERE s.request_id=r.id AND"
                  + " s.status='APPROVED')"
            : "r.created_at";
    var data =
        db.queryForList(
            "SELECT r.id,u.name user_name,d.name department_name,r.created_at,r.amount,(SELECT"
                + " count(*) FROM expense_items i WHERE i.request_id=r.id) item_count,(SELECT"
                + " string_agg(i.description,'；' ORDER BY i.line_no) FROM expense_items i WHERE"
                + " i.request_id=r.id) purposes,r.status,r.payment_status,r.payment_acted_at FROM"
                + " requests r JOIN users u ON u.id=r.user_id JOIN departments d ON"
                + " d.id=u.department_id WHERE r.kind='EXPENSE' AND r.user_id IN ("
                + ids
                + ") AND "
                + at
                + ">=? AND "
                + at
                + "<? AND (?='' OR r.status=?)"
                + (basis.equals("approved") ? " AND r.status='APPROVED'" : "")
                + " ORDER BY r.id LIMIT 10001",
            m.atDay(1).atStartOfDay(),
            m.plusMonths(1).atDay(1).atStartOfDay(),
            status,
            status);
    ApiException.require(data.size() <= 10000, "导出超过 10000 条，请缩小筛选范围");
    var rows = new ArrayList<List<Object>>();
    var total = BigDecimal.ZERO;
    for (var row : data) {
      var values = new ArrayList<Object>();
      for (var e : row.entrySet()) values.add(ExportController.value(e.getKey(), e.getValue()));
      values.set(
          8,
          "PAID".equals(row.get("payment_status"))
              ? "已支付"
              : "UNPAID".equals(row.get("payment_status")) ? "待支付" : "");
      rows.add(values);
      total = total.add((BigDecimal) row.get("amount"));
    }
    rows.add(Arrays.asList("合计", "", "", "", total, "", "", "", "", ""));
    return csv(
        "expenses-" + month,
        List.of("单号", "申请人", "部门", "提交时间", "金额", "明细条数", "用途摘要", "审批状态", "支付状态", "支付时间"),
        rows,
        a,
        "EXPORT_EXPENSES_MONTHLY");
  }
}
