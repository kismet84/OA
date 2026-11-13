package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workbench")
class WorkbenchController {
  final JdbcTemplate db;
  final Clock clock;
  @org.springframework.beans.factory.annotation.Autowired AttendanceService attendanceService;

  WorkbenchController(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  boolean company(Actor a) {
    return a.admin() || a.has("FINANCE");
  }

  boolean team(Actor a) {
    return company(a) || a.has("SUPERVISOR") || a.has("DEPT_HEAD");
  }

  // Current organization relationships also determine the scope of historical completions.
  Set<Long> scope(Actor a, Long departmentId, boolean enabled) {
    if (departmentId != null && !company(a)) throw new ApiException(403, "无权筛选部门");
    String depts =
        "WITH RECURSIVE d AS (SELECT id FROM departments WHERE "
            + (departmentId != null ? "id=?" : "head_id=?")
            + " UNION SELECT c.id FROM departments c JOIN d ON c.parent_id=d.id) SELECT id FROM d";
    List<Object> args = new ArrayList<>();
    String where;
    if (company(a)) {
      where = departmentId == null ? "true" : "department_id IN (" + depts + ")";
      if (departmentId != null) {
        ApiException.require(
            db.queryForObject(
                    "SELECT count(*) FROM departments WHERE id=?", Long.class, departmentId)
                > 0,
            "部门不存在");
        args.add(departmentId);
      }
    } else {
      where = "id=?";
      args.add(a.id());
      if (a.has("SUPERVISOR")) {
        where += " OR manager_id=?";
        args.add(a.id());
      }
      if (a.has("DEPT_HEAD")) {
        where += " OR department_id IN (" + depts + ")";
        args.add(a.id());
      }
    }
    Set<Long> ids =
        new LinkedHashSet<>(
            db.queryForList(
                "SELECT id FROM users WHERE (" + where + ")" + (enabled ? " AND enabled" : ""),
                Long.class,
                args.toArray()));
    return ids;
  }

  String ids(Set<Long> ids) {
    return ids.isEmpty() ? "NULL" : String.join(",", ids.stream().map(String::valueOf).toList());
  }

  @GetMapping("/members")
  Object members(
      @RequestParam(required = false) Long departmentId,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    ApiException.require(offset >= 0 && search.length() <= 100, "分页或搜索参数无效");
    return db.queryForList(
        "SELECT id,name,employee_no FROM users WHERE id IN ("
            + ids(scope(Auth.actor(req), departmentId, true))
            + ") AND (position(? in name)>0 OR position(? in employee_no)>0) ORDER BY id LIMIT 200"
            + " OFFSET ?",
        search,
        search,
        offset);
  }

  @GetMapping("/summary")
  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  Object summary(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "self") String scope,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) Long departmentId,
      HttpServletRequest req) {
    Actor a = Auth.actor(req);
    LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
    LocalDate start, end;
    try {
      start = from == null ? today.withDayOfMonth(1) : LocalDate.parse(from);
      end = to == null ? today.withDayOfMonth(today.lengthOfMonth()) : LocalDate.parse(to);
    } catch (java.time.DateTimeException e) {
      throw new ApiException(400, "日期无效，请使用年月日格式");
    }
    ApiException.require(
        start.getYear() >= 1
            && start.getYear() <= 9999
            && end.getYear() >= 1
            && end.getYear() <= 9999,
        "日期年份须为0001至9999");
    ApiException.require(
        !start.isAfter(end) && ChronoUnit.DAYS.between(start, end) < 366, "开始日期不得晚于结束日期，含首尾最多366天");
    ApiException.require(Set.of("self", "team").contains(scope), "统计范围无效");
    if (scope.equals("team") && !team(a)) throw new ApiException(403, "无权查看团队汇总");
    if (departmentId != null && (!company(a) || !scope.equals("team")))
      throw new ApiException(403, "无权筛选部门");
    Set<Long> active = scope(a, departmentId, true), historical = scope(a, departmentId, false);
    if (userId != null && !active.contains(userId)) throw new ApiException(403, "无权查看该员工汇总");
    boolean personal = scope.equals("self") && (userId == null || userId == a.id());
    if (scope.equals("self") || userId != null) {
      long id = userId == null ? a.id() : userId;
      active = Set.of(id);
      historical = active;
    }
    String users = ids(active), history = ids(historical);
    var attendance =
        db.queryForMap(
            "SELECT count(*) FILTER (WHERE kind='NORMAL') AS \"normalPunches\", count(*) FILTER"
                + " (WHERE kind='FIELD') AS \"fieldPunches\", count(DISTINCT"
                + " (user_id,punched_at::date)) AS \"recordedDays\" FROM punches WHERE user_id IN ("
                + users
                + ") AND punched_at>=? AND punched_at<?",
            start.atStartOfDay(),
            end.plusDays(1).atStartOfDay());
    // Clip at existing half-day boundaries. Each person's recorded date counts once.
    var days =
        db.queryForList(
            "SELECT r.kind,coalesce(t.name,'') AS"
                + " \"leaveType\",sum(((least(r.end_at::date,?::date)-greatest(r.start_at::date,?::date))*2"
                + " + CASE WHEN r.end_at::date>?::date OR r.end_at::time='18:00' THEN 2 ELSE 1 END"
                + " - CASE WHEN r.start_at::date>=?::date AND r.start_at::time='13:00' THEN 1 ELSE"
                + " 0 END)/2.0) AS days,"
                + " sum((((least(r.end_at::date,?::date)-greatest(r.start_at::date,?::date))*2 +"
                + " CASE WHEN r.end_at::date>?::date OR r.end_at::time='18:00' THEN 2 ELSE 1 END -"
                + " CASE WHEN r.start_at::date>=?::date AND r.start_at::time='13:00' THEN 1 ELSE 0"
                + " END)/2.0)*coalesce(r.overtime_ratio,1)) AS \"creditedDays\" FROM requests r"
                + " LEFT JOIN leave_types t ON t.id=r.type_id WHERE r.user_id IN ("
                + users
                + ") AND r.status='APPROVED' AND r.kind IN ('LEAVE','TRAVEL','OUTING','OVERTIME')"
                + " AND r.start_at<? AND r.end_at>=? GROUP BY r.kind,t.name ORDER BY r.kind,t.name",
            end,
            start,
            end,
            start,
            end,
            start,
            end,
            start,
            end.plusDays(1).atStartOfDay(),
            start.atStartOfDay());
    attendance.put("daysByType", days);
    for (String kind : List.of("LEAVE", "TRAVEL", "OUTING", "OVERTIME")) {
      var total = java.math.BigDecimal.ZERO;
      var credited = java.math.BigDecimal.ZERO;
      for (var row : days)
        if (kind.equals(row.get("kind"))) {
          total = total.add((java.math.BigDecimal) row.get("days"));
          credited = credited.add((java.math.BigDecimal) row.get("creditedDays"));
        }
      attendance.put(kind.toLowerCase() + "Days", total);
      if (kind.equals("OVERTIME")) attendance.put("overtimeCreditedDays", credited);
    }
    attendance.put(
        "approvedCorrections",
        db.queryForObject(
            "SELECT count(*) FROM requests WHERE user_id IN ("
                + users
                + ") AND status='APPROVED' AND kind='CORRECTION' AND start_at>=? AND start_at<?",
            Long.class,
            start.atStartOfDay(),
            end.plusDays(1).atStartOfDay()));
    var anomalies=new LinkedHashMap<String,Object>();
    for(String k:List.of("lateCount","earlyCount","missingCount"))anomalies.put(k,0);
    for(long uid:active){var stats=attendanceService.summary(uid,start,end);for(String k:anomalies.keySet())anomalies.put(k,((Number)anomalies.get(k)).intValue()+((Number)stats.get(k)).intValue());}
    attendance.put("anomalies",anomalies);
    long target = userId == null ? a.id() : userId;
    String relevant =
        personal
            ? "(r.user_id="
                + target
                + " OR EXISTS (SELECT 1 FROM approval_steps s WHERE s.request_id=r.id AND"
                + " s.approver_id="
                + target
                + "))"
            : "r.user_id IN (" + history + ")";
    String pendingRelevant =
        personal
            ? "(r.user_id="
                + target
                + " OR EXISTS (SELECT 1 FROM approval_steps s WHERE s.request_id=r.id AND"
                + " s.step=r.current_step AND s.approver_id="
                + target
                + "))"
            : "r.user_id IN (" + users + ")";
    String finished =
        " FROM requests r JOIN LATERAL (SELECT max(acted_at) at FROM approval_steps WHERE"
            + " request_id=r.id AND status IN ('APPROVED','REJECTED')) f ON true WHERE "
            + relevant
            + " AND r.status IN ('APPROVED','REJECTED') AND f.at>=? AND f.at<?";
    var approvals =
        db.queryForMap(
            "SELECT count(*) AS completed,coalesce(round(avg(extract(epoch FROM"
                + " (f.at-r.created_at))/3600)::numeric,2),0) AS \"averageHours\""
                + finished,
            start.atStartOfDay(),
            end.plusDays(1).atStartOfDay());
    approvals.put(
        "byType",
        db.queryForList(
            "SELECT r.kind,r.status,count(*) AS completed,coalesce(round(avg(extract(epoch FROM"
                + " (f.at-r.created_at))/3600)::numeric,2),0) AS \"averageHours\""
                + finished
                + " GROUP BY r.kind,r.status ORDER BY r.kind,r.status",
            start.atStartOfDay(),
            end.plusDays(1).atStartOfDay()));
    var now = LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
    approvals.put(
        "waiting",
        db.queryForMap(
            "SELECT count(*) AS total,count(*) FILTER (WHERE created_at>=?) AS"
                + " \"withinOneDay\",count(*) FILTER (WHERE created_at<? AND created_at>=?) AS"
                + " \"oneToThreeDays\",count(*) FILTER (WHERE created_at<?) AS \"overThreeDays\""
                + " FROM requests r WHERE status='PENDING' AND "
                + pendingRelevant,
            now.minusDays(1),
            now.minusDays(1),
            now.minusDays(3),
            now.minusDays(3)));
    approvals.put(
        "initiatedPending",
        db.queryForObject(
            "SELECT count(*) FROM requests WHERE status='PENDING' AND user_id IN (" + users + ")",
            Long.class));
    approvals.put(
        "pendingForMe",
        db.queryForObject(
            "SELECT count(*) FROM requests r JOIN approval_steps s ON s.request_id=r.id AND"
                + " s.step=r.current_step WHERE r.status='PENDING' AND s.status='PENDING' AND"
                + " s.approver_id=?",
            Long.class,
            a.id()));
    return Map.of(
        "from",
        start,
        "to",
        end,
        "scope",
        scope,
        "canViewTeam",
        team(a),
        "canFilterDepartment",
        company(a),
        "people",
        active.size(),
        "attendance",
        attendance,
        "approvals",
        approvals,
        "departments",
        company(a)
            ? db.queryForList("SELECT id,name,parent_id FROM departments ORDER BY id")
            : List.of());
  }
}
