package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class AttendanceController {
  final AttendanceService service;
  final JdbcTemplate db;
  final Auth auth;
  final WorkbenchController workbench;

  AttendanceController(AttendanceService s, JdbcTemplate db, Auth a, WorkbenchController w) {
    service = s;
    this.db = db;
    auth = a;
    workbench = w;
  }

  record Rules(
      boolean enabled,
      @NotNull LocalTime work_start,
      @NotNull LocalTime work_end,
      @Min(0) @Max(240) int late_grace_minutes,
      @Min(0) @Max(240) int early_grace_minutes,
      @NotBlank String workdays,
      @NotNull LocalDate effective_from) {}

  record Day(@NotNull LocalDate day, @NotBlank String kind, @Size(max = 100) String name) {}

  @GetMapping("/admin/attendance-rules")
  Object rules(HttpServletRequest r) {
    Auth.actor(r).requireAdmin();
    return service.rules();
  }

  @PutMapping("/admin/attendance-rules")
  @Transactional
  Object save(@Valid @RequestBody Rules in, HttpServletRequest r) {
    var a = Auth.actor(r);
    a.requireAdmin();
    ApiException.require(in.work_start().isBefore(in.work_end()), "上班时间必须早于下班时间");
    ApiException.require(in.workdays().matches("[1-7](,[1-7]){0,6}"), "工作日须为1至7，以英文逗号分隔");
    db.update(
        "UPDATE attendance_rules SET"
            + " enabled=?,work_start=?,work_end=?,late_grace_minutes=?,early_grace_minutes=?,workdays=string_to_array(?,',')::integer[],effective_from=?,updated_at=now(),updated_by=?"
            + " WHERE id=1",
        in.enabled(),
        in.work_start(),
        in.work_end(),
        in.late_grace_minutes(),
        in.early_grace_minutes(),
        in.workdays(),
        in.effective_from(),
        a.id());
    auth.audit(a.id(), "SAVE_ATTENDANCE_RULES", 1);
    return service.rules();
  }

  @GetMapping({"/admin/work-calendar", "/work-calendar"})
  Object calendar(@RequestParam int year, HttpServletRequest r) {
    if (r.getRequestURI().contains("/admin/")) Auth.actor(r).requireAdmin();
    ApiException.require(year >= 1 && year < 9999, "年份无效");
    return db.queryForList(
        "SELECT * FROM work_calendar WHERE day>=? AND day<? ORDER BY day",
        LocalDate.of(year, 1, 1),
        LocalDate.of(year + 1, 1, 1));
  }

  void upsert(Day in, long actor) {
    ApiException.require(
        Set.of("HOLIDAY", "WORKDAY").contains(in.kind()), "日期类型须为 HOLIDAY 或 WORKDAY");
    db.update(
        "INSERT INTO work_calendar(day,kind,name,created_by) VALUES(?,?,?,?) ON CONFLICT(day) DO"
            + " UPDATE SET"
            + " kind=excluded.kind,name=excluded.name,created_by=excluded.created_by,created_at=now()",
        in.day(),
        in.kind(),
        in.name() == null ? "" : in.name(),
        actor);
  }

  @PostMapping("/admin/work-calendar")
  @Transactional
  Object day(@Valid @RequestBody Day in, HttpServletRequest r) {
    var a = Auth.actor(r);
    a.requireAdmin();
    upsert(in, a.id());
    auth.audit(a.id(), "SAVE_WORK_CALENDAR", in.day());
    return Map.of("message", "已保存");
  }

  record Batch(@NotBlank @Size(max = 100000) String text) {}

  @PostMapping("/admin/work-calendar/batch")
  @Transactional
  Object batch(@Valid @RequestBody Batch in, HttpServletRequest r) {
    var a = Auth.actor(r);
    a.requireAdmin();
    var lines = in.text().strip().split("\\R");
    ApiException.require(lines.length <= 400, "每次最多400行");
    var results = new ArrayList<Map<String, Object>>();
    int n = 0, success = 0;
    for (String line : lines) {
      n++;
      try {
        String[] p = line.split(",", 3);
        ApiException.require(p.length >= 2, "格式：日期,类型,名称");
        LocalDate date = LocalDate.parse(p[0].trim());
        String kind = p[1].trim(), name = p.length == 3 ? p[2].trim() : "";
        ApiException.require(name.length() <= 100, "名称最多100字");
        upsert(new Day(date, kind, name), a.id());
        results.add(Map.of("line", n, "success", true));
        success++;
      } catch (DateTimeException | ApiException e) {
        results.add(
            Map.of(
                "line",
                n,
                "success",
                false,
                "message",
                e instanceof DateTimeException ? "日期格式无效" : e.getMessage()));
      }
    }
    auth.audit(a.id(), "BATCH_WORK_CALENDAR", success);
    return Map.of("success", success, "failed", n - success, "rows", results);
  }

  @DeleteMapping("/admin/work-calendar/{day}")
  @Transactional
  Object delete(@PathVariable LocalDate day, HttpServletRequest r) {
    var a = Auth.actor(r);
    a.requireAdmin();
    db.update("DELETE FROM work_calendar WHERE day=?", day);
    auth.audit(a.id(), "DELETE_WORK_CALENDAR", day);
    return Map.of("message", "已删除");
  }

  Set<Long> authorized(Actor a, Long department, Long user) {
    var ids = workbench.scope(a, department, false);
    if (user != null) {
      if (!ids.contains(user)) throw new ApiException(403, "无权查看该员工考勤");
      return Set.of(user);
    }
    return ids;
  }

  @GetMapping("/attendance/daily")
  Object daily(
      @RequestParam(required = false) Long userId,
      @RequestParam LocalDate from,
      @RequestParam LocalDate to,
      HttpServletRequest r) {
    var a = Auth.actor(r);
    long id = userId == null ? a.id() : userId;
    authorized(a, null, id);
    return service.daily(id, from, to);
  }

  @GetMapping("/attendance/summary")
  Object summary(
      @RequestParam String month,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(required = false) Long userId,
      HttpServletRequest r) {
    var m = AttendanceService.month(month);
    return authorized(Auth.actor(r), departmentId, userId).stream()
        .map(id -> service.summary(id, m.atDay(1), m.atEndOfMonth()))
        .toList();
  }
}
