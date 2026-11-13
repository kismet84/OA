package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AttendanceServiceTest {
  final LocalDate day = LocalDate.of(2026, 11, 2);
  final AttendanceService.Rule rule =
      new AttendanceService.Rule(
          true,
          LocalTime.of(9, 0),
          LocalTime.of(18, 0),
          5,
          5,
          Set.of(1, 2, 3, 4, 5),
          day.minusDays(1));

  Map<String, Object> run(
      AttendanceService.Rule r,
      LocalDate d,
      String calendar,
      List<AttendanceService.Absence> absence,
      String... punches) {
    return AttendanceService.evaluate(
        r,
        d,
        day.minusYears(1),
        true,
        null,
        calendar,
        Arrays.stream(punches).map(t -> d.atTime(LocalTime.parse(t))).toList(),
        absence,
        d.plusDays(1).atStartOfDay());
  }

  Map<String, Object> check(String state, String... punches) {
    var out = run(rule, day, null, List.of(), punches);
    assertEquals(state, out.get("status"));
    return out;
  }

  AttendanceService.Absence absent(String kind, String start, String end) {
    return new AttendanceService.Absence(
        1, kind, day.atTime(LocalTime.parse(start)), day.atTime(LocalTime.parse(end)));
  }

  @Test
  void normal() {
    check("NORMAL", "09:00", "18:00");
  }

  @Test
  void graceBoundary() {
    check("NORMAL", "09:05", "17:55");
  }

  @Test
  void late() {
    assertEquals(6, check("LATE", "09:06", "18:00").get("lateMinutes"));
  }

  @Test
  void early() {
    assertEquals(6, check("EARLY", "09:00", "17:54").get("earlyMinutes"));
  }

  @Test
  void both() {
    check("LATE_EARLY", "09:10", "17:30");
  }

  @Test
  void noPunch() {
    assertEquals("上班卡、下班卡", check("MISSING").get("missingPunch"));
  }

  @Test
  void missingEnd() {
    assertEquals("下班卡", check("MISSING", "09:00").get("missingPunch"));
  }

  @Test
  void missingStart() {
    assertEquals("上班卡", check("MISSING", "18:00").get("missingPunch"));
  }

  @Test
  void fullLeave() {
    assertEquals(
        "LEAVE", run(rule, day, null, List.of(absent("LEAVE", "09:00", "18:00"))).get("status"));
  }

  @Test
  void morningLeave() {
    assertEquals(
        "NORMAL",
        run(rule, day, null, List.of(absent("LEAVE", "09:00", "13:00")), "13:00", "18:00")
            .get("status"));
  }

  @Test
  void afternoonLeave() {
    assertEquals(
        "NORMAL",
        run(rule, day, null, List.of(absent("LEAVE", "13:00", "18:00")), "09:00", "13:00")
            .get("status"));
  }

  @Test
  void combinedHalfLeaves() {
    assertEquals(
        "LEAVE",
        run(
                rule,
                day,
                null,
                List.of(absent("LEAVE", "09:00", "13:00"), absent("LEAVE", "13:00", "18:00")))
            .get("status"));
  }

  @Test
  void travelWithPunch() {
    assertEquals(
        "FIELD",
        run(rule, day, null, List.of(absent("TRAVEL", "09:00", "18:00")), "10:00").get("status"));
  }

  @Test
  void outingWithoutPunch() {
    assertEquals(
        "MISSING", run(rule, day, null, List.of(absent("OUTING", "09:00", "18:00"))).get("status"));
  }

  @Test
  void holiday() {
    assertEquals("REST", run(rule, day, "HOLIDAY", List.of()).get("status"));
  }

  @Test
  void weekend() {
    assertEquals("REST", run(rule, day.plusDays(5), null, List.of()).get("status"));
  }

  @Test
  void makeupWorkday() {
    assertEquals("MISSING", run(rule, day.plusDays(5), "WORKDAY", List.of()).get("status"));
  }

  @Test
  void correctedPunchesAreOrdinaryFacts() {
    check("NORMAL", "09:00", "18:00");
  }

  @Test
  void disabled() {
    var r =
        new AttendanceService.Rule(
            false, rule.start(), rule.end(), 5, 5, rule.days(), rule.effective());
    assertEquals("RECORDED", run(r, day, null, List.of()).get("status"));
  }

  @Test
  void beforeEffective() {
    assertEquals("EXCLUDED", run(rule, day.minusMonths(1), null, List.of()).get("status"));
  }

  @Test
  void todayUnfinished() {
    assertEquals(
        "IN_PROGRESS",
        AttendanceService.evaluate(
                rule,
                day,
                day.minusDays(1),
                true,
                null,
                null,
                List.of(day.atTime(17, 0)),
                List.of(),
                day.atTime(17, 30))
            .get("status"));
  }

  @Test
  void future() {
    assertEquals(
        "FUTURE",
        AttendanceService.evaluate(
                rule,
                day,
                day.minusDays(1),
                true,
                null,
                null,
                List.of(),
                List.of(),
                day.minusDays(1).atTime(18, 0))
            .get("status"));
  }

  @Test
  void beforeEmployment() {
    assertEquals(
        "EXCLUDED",
        AttendanceService.evaluate(
                rule,
                day,
                day.plusDays(1),
                true,
                null,
                null,
                List.of(),
                List.of(),
                day.plusDays(1).atTime(18, 0))
            .get("status"));
  }

  @Test
  void disabledUser() {
    assertEquals(
        "EXCLUDED",
        AttendanceService.evaluate(
                rule,
                day,
                day.minusDays(1),
                false,
                null,
                null,
                List.of(),
                List.of(),
                day.plusDays(1).atTime(18, 0))
            .get("status"));
  }

  @Test
  void crossMonth() {
    var d = LocalDate.of(2026, 11, 30);
    assertEquals("NORMAL", run(rule, d, null, List.of(), "09:00", "18:00").get("status"));
    assertEquals("MISSING", run(rule, d.plusDays(1), null, List.of()).get("status"));
  }

  @Test
  void disabledDateSegmentsAndReactivation() {
    for (int offset : List.of(-1, 0, 1)) {
      var date = day.plusDays(offset);
      var result =
          AttendanceService.evaluate(
              rule,
              date,
              day.minusYears(1),
              false,
              day,
              "WORKDAY",
              List.of(),
              List.of(),
              day.plusDays(2).atStartOfDay());
      assertEquals(offset < 0 ? "MISSING" : "EXCLUDED", result.get("status"));
    }
    assertEquals("MISSING", run(rule, day, null, List.of()).get("status"));
    assertEquals(day, AttendanceService.beijingDate(OffsetDateTime.parse("2026-11-01T16:01:00Z")));
    assertEquals(
        day,
        AttendanceService.beijingDate(
            java.sql.Timestamp.from(Instant.parse("2026-11-01T16:01:00Z"))));
  }

  @Test
  void customHoursLeaveBoundariesAndMixedField() {
    for (String end : List.of("17:30", "18:30")) {
      var custom =
          new AttendanceService.Rule(
              true, LocalTime.of(8, 30), LocalTime.parse(end), 5, 5, rule.days(), rule.effective());
      for (String leaveEnd : end.equals("17:30") ? List.of("17:30", "18:00") : List.of("18:00")) {
        var full = run(custom, day, null, List.of(absent("LEAVE", "09:00", leaveEnd)));
        assertEquals("LEAVE", full.get("status"));
        assertEquals(1.0, full.get("leaveDays"));
        var pm =
            run(custom, day, null, List.of(absent("LEAVE", "13:00", leaveEnd)), "08:30", "13:00");
        assertEquals("NORMAL", pm.get("status"));
        assertEquals(0.5, pm.get("leaveDays"));
      }
      var am = run(custom, day, null, List.of(absent("LEAVE", "09:00", "13:00")), "13:00", end);
      assertEquals("NORMAL", am.get("status"));
      assertEquals(0.5, am.get("leaveDays"));
      for (String kind : List.of("OUTING", "TRAVEL")) {
        for (boolean morningLeave : List.of(true, false)) {
          var absences =
              List.of(
                  absent(morningLeave ? "LEAVE" : kind, "09:00", "13:00"),
                  absent(morningLeave ? kind : "LEAVE", "13:00", "18:00"));
          for (boolean punch : List.of(true, false)) {
            var mixed =
                run(custom, day, null, absences, punch ? new String[] {"14:00"} : new String[] {});
            assertEquals(0.5, mixed.get("leaveDays"));
            assertEquals(true, mixed.get("field"));
            assertEquals(punch ? "FIELD" : "MISSING", mixed.get("status"));
          }
        }
      }
    }
  }
}
