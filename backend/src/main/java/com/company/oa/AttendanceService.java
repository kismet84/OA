package com.company.oa;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** All local dates and punch timestamps are interpreted in Beijing time. No historical writes. */
@Service
class AttendanceService {
  static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
  final JdbcTemplate db;
  final Clock clock;

  AttendanceService(JdbcTemplate db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  Map<String, Object> rules() {
    return db.queryForMap(
        "SELECT"
            + " id,enabled,work_start::text,work_end::text,late_grace_minutes,early_grace_minutes,array_to_string(workdays,',')"
            + " workdays,effective_from,updated_at,updated_by FROM attendance_rules WHERE id=1");
  }

  record Rule(
      boolean enabled,
      LocalTime start,
      LocalTime end,
      int late,
      int early,
      Set<Integer> days,
      LocalDate effective) {
    static Rule of(Map<String, Object> r) {
      var days = new HashSet<Integer>();
      for (String d : r.get("workdays").toString().split(",")) days.add(Integer.parseInt(d));
      return new Rule(
          (boolean) r.get("enabled"),
          LocalTime.parse(r.get("work_start").toString()),
          LocalTime.parse(r.get("work_end").toString()),
          ((Number) r.get("late_grace_minutes")).intValue(),
          ((Number) r.get("early_grace_minutes")).intValue(),
          days,
          LocalDate.parse(r.get("effective_from").toString()));
    }
  }

  record Absence(long id, String kind, LocalDateTime start, LocalDateTime end) {}

  static LocalDateTime time(Object v) {
    return v instanceof java.sql.Timestamp t
        ? t.toLocalDateTime()
        : LocalDateTime.parse(v.toString().replace(' ', 'T'));
  }

  static LocalDate beijingDate(Object value) {
    if (value == null) return null;
    Instant instant =
        value instanceof java.sql.Timestamp t
            ? t.toInstant()
            : value instanceof OffsetDateTime o
                ? o.toInstant()
                : value instanceof Instant i
                    ? i
                    : OffsetDateTime.parse(value.toString()).toInstant();
    return instant.atZone(ZONE).toLocalDate();
  }

  static Map<String, Object> evaluate(
      Rule rule,
      LocalDate day,
      LocalDate created,
      boolean enabled,
      LocalDate disabledDate,
      String calendar,
      List<LocalDateTime> punches,
      List<Absence> absences,
      LocalDateTime now) {
    var out = new LinkedHashMap<String, Object>();
    var sorted = punches.stream().sorted().toList();
    LocalDateTime first = sorted.isEmpty() ? null : sorted.getFirst(),
        last = sorted.isEmpty() ? null : sorted.getLast();
    out.put("day", day);
    out.put("firstPunch", first);
    out.put("lastPunch", last);
    out.put("punches", sorted.size());
    out.put("rulesEnabled", rule.enabled());
    out.put("lateMinutes", 0);
    out.put("earlyMinutes", 0);
    out.put("missingPunch", "");
    out.put("expected", false);
    out.put("leaveDays", 0.0);
    out.put("field", false);
    var linked =
        absences.stream()
            .filter(
                a -> !a.start().toLocalDate().isAfter(day) && !a.end().toLocalDate().isBefore(day))
            .toList();
    out.put("requestIds", linked.stream().map(Absence::id).toList());
    boolean am = false, pm = false, field = false;
    // Workflow half-days end at 18:00 even when the attendance rule ends later.
    LocalTime pmEnd = rule.end().isBefore(LocalTime.of(18, 0)) ? rule.end() : LocalTime.of(18, 0);
    for (var a : linked) {
      if (a.kind().equals("LEAVE")) {
        am |= a.start().isBefore(day.atTime(13, 0)) && !a.end().isBefore(day.atTime(13, 0));
        pm |= !a.start().isAfter(day.atTime(13, 0)) && !a.end().isBefore(day.atTime(pmEnd));
      } else field = true;
    }
    out.put("leaveDays", (am ? .5 : 0) + (pm ? .5 : 0));
    out.put("field", field);
    String status;
    if (day.isAfter(now.toLocalDate())) status = "FUTURE";
    else if (day.isBefore(rule.effective())
        || day.isBefore(created)
        || (!enabled && disabledDate == null)
        || (disabledDate != null && !day.isBefore(disabledDate))) status = "EXCLUDED";
    else if (!rule.enabled())
      status = am && pm ? "LEAVE" : sorted.isEmpty() ? "RECORDED" : "PUNCHED";
    else if ("HOLIDAY".equals(calendar)
        || (!"WORKDAY".equals(calendar) && !rule.days().contains(day.getDayOfWeek().getValue())))
      status = "REST";
    else {
      out.put("expected", true);
      LocalTime start = am ? LocalTime.of(13, 0) : rule.start(),
          end = pm ? LocalTime.of(13, 0) : rule.end();
      if (am && pm) status = "LEAVE";
      else if (day.equals(now.toLocalDate()) && now.toLocalTime().isBefore(end))
        status = "IN_PROGRESS";
      else if (field) {
        status = sorted.isEmpty() ? "MISSING" : "FIELD";
        if (sorted.isEmpty()) out.put("missingPunch", "外勤卡");
      } else if (sorted.size() < 2) {
        status = "MISSING";
        out.put(
            "missingPunch",
            sorted.isEmpty()
                ? "上班卡、下班卡"
                : Math.abs(ChronoUnit.MINUTES.between(start, first.toLocalTime()))
                        <= Math.abs(ChronoUnit.MINUTES.between(first.toLocalTime(), end))
                    ? "下班卡"
                    : "上班卡");
      } else {
        int late =
            first.toLocalTime().isAfter(start.plusMinutes(rule.late()))
                ? (int) Math.max(0, ChronoUnit.MINUTES.between(start, first.toLocalTime()))
                : 0;
        int early =
            last.toLocalTime().isBefore(end.minusMinutes(rule.early()))
                ? (int) Math.max(0, ChronoUnit.MINUTES.between(last.toLocalTime(), end))
                : 0;
        out.put("lateMinutes", late);
        out.put("earlyMinutes", early);
        status =
            late > 0 && early > 0
                ? "LATE_EARLY"
                : late > 0 ? "LATE" : early > 0 ? "EARLY" : "NORMAL";
      }
    }
    out.put("status", status);
    return out;
  }

  List<Map<String, Object>> daily(long user, LocalDate from, LocalDate to) {
    ApiException.require(
        !from.isAfter(to) && ChronoUnit.DAYS.between(from, to) < 366, "日期范围最多366天，开始不得晚于结束");
    var u = db.queryForMap("SELECT enabled,created_at,disabled_at FROM users WHERE id=?", user);
    var rule = Rule.of(rules());
    var punches =
        db
            .queryForList(
                "SELECT punched_at FROM punches WHERE user_id=? AND punched_at>=? AND punched_at<?"
                    + " ORDER BY punched_at",
                user,
                from.atStartOfDay(),
                to.plusDays(1).atStartOfDay())
            .stream()
            .map(r -> time(r.get("punched_at")))
            .toList();
    var absences =
        db
            .queryForList(
                "SELECT id,kind,start_at,end_at FROM requests WHERE user_id=? AND status='APPROVED'"
                    + " AND kind IN ('LEAVE','TRAVEL','OUTING') AND start_at<? AND end_at>=?",
                user,
                to.plusDays(1).atStartOfDay(),
                from.atStartOfDay())
            .stream()
            .map(
                r ->
                    new Absence(
                        ((Number) r.get("id")).longValue(),
                        r.get("kind").toString(),
                        time(r.get("start_at")),
                        time(r.get("end_at"))))
            .toList();
    var calendar = new HashMap<String, String>();
    for (var c :
        db.queryForList("SELECT day,kind FROM work_calendar WHERE day BETWEEN ? AND ?", from, to))
      calendar.put(c.get("day").toString(), c.get("kind").toString());
    var now = LocalDateTime.now(clock.withZone(ZONE));
    return from.datesUntil(to.plusDays(1))
        .map(
            d ->
                evaluate(
                    rule,
                    d,
                    time(u.get("created_at")).toLocalDate(),
                    (boolean) u.get("enabled"),
                    beijingDate(u.get("disabled_at")),
                    calendar.get(d.toString()),
                    punches.stream().filter(p -> p.toLocalDate().equals(d)).toList(),
                    absences,
                    now))
        .toList();
  }

  Map<String, Object> summary(long user, LocalDate from, LocalDate to) {
    var out =
        new LinkedHashMap<>(
            db.queryForMap(
                "SELECT u.id user_id,u.employee_no,u.name user_name,d.name department_name FROM"
                    + " users u JOIN departments d ON d.id=u.department_id WHERE u.id=?",
                user));
    var days = daily(user, from, to);
    for (String key :
        List.of(
            "expectedDays",
            "normalDays",
            "lateCount",
            "lateMinutes",
            "earlyCount",
            "earlyMinutes",
            "missingCount",
            "fieldDays",
            "restDays")) out.put(key, 0);
    double leave = 0;
    for (var d : days) {
      String s = d.get("status").toString();
      if (s.equals("EXCLUDED") || s.equals("FUTURE")) continue;
      if (Boolean.TRUE.equals(d.get("expected"))) inc(out, "expectedDays", 1);
      if (!s.equals("REST")) leave += ((Number) d.get("leaveDays")).doubleValue();
      if (s.equals("NORMAL")) inc(out, "normalDays", 1);
      if (s.equals("MISSING")) inc(out, "missingCount", 1);
      if (s.equals("FIELD")) inc(out, "fieldDays", 1);
      if (s.equals("REST")) inc(out, "restDays", 1);
      for (String prefix : List.of("late", "early")) {
        int n = ((Number) d.get(prefix + "Minutes")).intValue();
        if (n > 0) {
          inc(out, prefix + "Count", 1);
          inc(out, prefix + "Minutes", n);
        }
      }
    }
    out.put("leaveDays", leave);
    out.put("rulesEnabled", Rule.of(rules()).enabled());
    return out;
  }

  static void inc(Map<String, Object> m, String k, int n) {
    m.put(k, ((Number) m.get(k)).intValue() + n);
  }

  static YearMonth month(String s) {
    try {
      return YearMonth.parse(s);
    } catch (DateTimeException e) {
      throw new ApiException(400, "月份无效，请使用 YYYY-MM");
    }
  }
}
