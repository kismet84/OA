package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class Phase2AttendanceTest {
  final JdbcTemplate db = mock(JdbcTemplate.class);
  final Auth auth = mock(Auth.class);
  final Clock clock = Clock.fixed(Instant.parse("2026-11-11T08:00:00Z"), ZoneId.of("Asia/Shanghai"));
  final WorkflowService service = new WorkflowService(db, auth, clock);

  @Test
  void overtimeCountsWeekendAndCrossYearInHalfDaysWithoutChangingOldRules() {
    var start = LocalDateTime.parse("2026-12-31T13:00");
    var end = LocalDateTime.parse("2027-01-03T18:00");
    assertEquals(0, new BigDecimal("3.5").compareTo(WorkflowService.calendarDays(start, end, true)));
    assertThrows(ApiException.class, () -> WorkflowService.days(start, end));
    assertThrows(ApiException.class, () -> WorkflowService.calendarDays(start, null, true));
    assertThrows(ApiException.class, () -> WorkflowService.calendarDays(start, start, true));
    assertThrows(ApiException.class, () -> WorkflowService.calendarDays(start.plusMinutes(1), end, true));
  }

  @Test
  void deadlineIncludesWholeThirdShanghaiDateAndRejectsFourthDate() {
    var at = LocalDateTime.parse("2026-11-08T00:01");
    assertDoesNotThrow(() -> WorkflowService.validateCorrectionTime(at, LocalDateTime.parse("2026-11-11T23:59:59"), 3));
    assertThrows(ApiException.class, () -> WorkflowService.validateCorrectionTime(at, LocalDateTime.parse("2026-11-12T00:00"), 3));
    assertDoesNotThrow(() -> WorkflowService.validateCorrectionTime(at, at, 0));
    assertThrows(ApiException.class, () -> WorkflowService.validateCorrectionTime(at, at.minusSeconds(1), 3));
    assertThrows(ApiException.class, () -> WorkflowService.validateCorrectionTime(at, at.plusDays(1), 0));
  }

  @Test
  void correctionUsesHistoricalHalfOpenEligibilityAndExcludesItself() {
    var at = LocalDateTime.parse("2026-11-10T13:21");
    when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    // No location/network or leave conflict query is needed for a normal correction.
    service.checkCorrection(8, at, "NORMAL", 42);
    verify(db).queryForObject(contains("punched_at=? AND kind=?"), eq(Long.class), eq(8L), eq(at), eq("NORMAL"));
    verify(db).queryForObject(contains("id<>?"), eq(Long.class), eq(8L), eq(at), eq("NORMAL"), eq(42L));
    assertThrows(ApiException.class, () -> service.checkCorrection(8, at, "FIELD", 42));
    verify(db).queryForObject(contains("start_at<=? AND end_at>?"), eq(Long.class), eq(8L), eq(at), eq(at));
  }

  @Test
  void finalApprovalRechecksFieldEligibilityAndDoesNotWritePunchIfItIsGone() {
    var at = LocalDateTime.parse("2026-11-10T13:21");
    var request = new HashMap<String, Object>();
    request.put("kind", "CORRECTION");
    request.put("punch_kind", "FIELD");
    request.put("status", "PENDING");
    request.put("user_id", 8L);
    request.put("current_step", 1);
    request.put("start_at", Timestamp.valueOf(at));
    when(db.queryForList("SELECT * FROM requests WHERE id=? FOR UPDATE", 42L)).thenReturn(List.of(request));
    when(db.queryForMap("SELECT * FROM approval_steps WHERE request_id=? AND step=?", 42L, 1))
        .thenReturn(Map.of("approver_id", 9L));
    when(db.queryForList("SELECT * FROM users WHERE id=? FOR UPDATE", 8L))
        .thenReturn(List.of(Map.of("enabled", true)));
    when(db.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    when(db.queryForObject("SELECT count(*) FROM requests r WHERE r.id=? AND TRUE", Long.class, 42L))
        .thenReturn(1L);
    assertThrows(ApiException.class, () -> service.decide(42, new WorkflowService.Decision(true, ""), new Actor(9, "管理员", "ADMIN", false, 0)));
    verify(db).queryForObject(contains("start_at<=? AND end_at>?"), eq(Long.class), eq(8L), eq(at), eq(at));
    verify(db, never()).update(startsWith("INSERT INTO punches"), any(Object[].class));
    verify(db, never()).update(startsWith("UPDATE requests SET status"), any(Object[].class));
  }
}
