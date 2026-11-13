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
}
