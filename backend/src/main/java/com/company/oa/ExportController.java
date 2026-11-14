package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/exports")
@AdminOnly
class ExportController {
  static final Map<String, String> HEADERS =
      Map.ofEntries(
          Map.entry("id", "记录编号"),
          Map.entry("user_id", "员工编号"),
          Map.entry("employee_no", "工号"),
          Map.entry("user_name", "姓名"),
          Map.entry("kind", "类型"),
          Map.entry("type_id", "假期类型编号"),
          Map.entry("type_name", "假期名称"),
          Map.entry("start_at", "开始时间"),
          Map.entry("end_at", "结束时间"),
          Map.entry("days", "天数"),
          Map.entry("amount", "金额（元）"),
          Map.entry("reason", "申请理由"),
          Map.entry("attachment_id", "证明文件编号"),
          Map.entry("status", "状态"),
          Map.entry("current_step", "当前步骤"),
          Map.entry("approver_id", "当前审批人编号"),
          Map.entry("deduct_balance", "扣减余额"),
          Map.entry("annual_limit", "年度上限"),
          Map.entry("created_at", "创建时间"),
          Map.entry("punched_at", "打卡时间"),
          Map.entry("located_at", "定位采集时间"), Map.entry("locatedAt", "定位采集时间（UTC）"),
          Map.entry("location_source", "定位来源"), Map.entry("locationSource", "定位来源"),
          Map.entry("latitude", "纬度"),
          Map.entry("longitude", "经度"),
          Map.entry("accuracy", "精度（米）"),
          Map.entry("ssid", "Wi-Fi 名称"),
          Map.entry("exit_ip", "出口 IP"),
          Map.entry("punch_kind", "补卡类型"),
          Map.entry("overtime_ratio", "加班兑换比例"),
          Map.entry("credited_days", "兑换调休天数"),
          Map.entry("correction_request_id", "补卡申请编号（空为现场打卡）"),
          Map.entry("simulated", "模拟记录"));
  static final Map<String, String> VALUES =
      Map.ofEntries(
          Map.entry("LIVE", "现场实时"), Map.entry("WARMUP_CACHE", "预热缓存"),
          Map.entry("LAST_KNOWN", "系统缓存"), Map.entry("STALE_FALLBACK", "超时旧缓存"), Map.entry("UNKNOWN", "未知/历史记录"),
          Map.entry("LEAVE", "请假"),
          Map.entry("OVERTIME", "加班"),
          Map.entry("CORRECTION", "补卡"),
          Map.entry("TRAVEL", "出差"),
          Map.entry("OUTING", "外出"),
          Map.entry("EXPENSE", "报销"),
          Map.entry("NORMAL", "正常打卡"),
          Map.entry("FIELD", "出勤"),
          Map.entry("PENDING", "待审批"),
          Map.entry("APPROVED", "已批准"),
          Map.entry("REJECTED", "已驳回"),
          Map.entry("WITHDRAWN", "已撤回"),
          Map.entry("CANCELLED", "已撤销"));

  static String value(String key, Object v) {
    if (v instanceof Boolean b) return b ? "是" : "否";
    if (v != null && Set.of("kind", "status", "punch_kind", "location_source", "locationSource").contains(key))
      return VALUES.getOrDefault(v.toString(), v.toString());
    return v == null ? "" : v.toString();
  }

  final JdbcTemplate db;
  final RequestController requests;
  final PunchController punches;
  final Auth auth;

  ExportController(
      JdbcTemplate db, RequestController requests, PunchController punches, Auth auth) {
    this.db = db;
    this.requests = requests;
    this.punches = punches;
    this.auth = auth;
  }

  @GetMapping("/requests.csv")
  ResponseEntity<String> requests(
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(required = false) Long userId,
      HttpServletRequest req) {
    var q = requests.requestQuery(Auth.actor(req), status, kind, userId, false);
    String from=req.getParameter("from"),to=req.getParameter("to");
    try {if(from!=null&&!from.isBlank()){q=new RequestController.Query(q.sql()+" AND r.created_at>=?",q.params());q.params().add(LocalDate.parse(from).atStartOfDay());}
    if(to!=null&&!to.isBlank()){q=new RequestController.Query(q.sql()+" AND r.created_at<?",q.params());q.params().add(LocalDate.parse(to).plusDays(1).atStartOfDay());}}catch(java.time.DateTimeException e){throw new ApiException(400,"日期格式无效");}
    return csv("requests", q, Auth.actor(req));
  }

  @GetMapping("/punches.csv")
  ResponseEntity<String> punches(
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "") String kind,
      HttpServletRequest req) {
    return csv("punches", punches.query(Auth.actor(req), userId, from, to, kind), Auth.actor(req));
  }

  ResponseEntity<String> csv(String name, RequestController.Query q, Actor actor) {
    var rows = db.queryForList(q.sql() + " LIMIT 10001", q.params().toArray());
    ApiException.require(rows.size() <= 10000, "导出超过 10000 条，请缩小筛选范围");
    StringBuilder out = new StringBuilder("\ufeff");
    if (!rows.isEmpty()) {
      out.append(
              String.join(
                  ",",
                  rows.getFirst().keySet().stream()
                      .map(k -> CsvCells.cell(HEADERS.getOrDefault(k, k)))
                      .toList()))
          .append("\r\n");
      for (var r : rows)
        out.append(
                String.join(
                    ",",
                    r.entrySet().stream().map(e -> CsvCells.cell(value(e.getKey(), e.getValue()))).toList()))
            .append("\r\n");
    }
    auth.audit(actor.id(), "EXPORT_" + name.toUpperCase(), rows.size());
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
        .header("Content-Disposition", "attachment; filename=\"" + name + ".csv\"")
        .body(out.toString());
  }

}
