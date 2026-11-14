package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class WorkflowServiceTest {
  @Test
  void halfDayAndCalendarDaysCannotBeUnderReported() {
    assertEquals(
        new BigDecimal("0.5"),
        WorkflowService.days(
            LocalDateTime.parse("2026-11-12T09:00"), LocalDateTime.parse("2026-11-12T13:00")));
    assertEquals(
        new BigDecimal("0.5"),
        WorkflowService.days(
            LocalDateTime.parse("2026-11-12T13:00"), LocalDateTime.parse("2026-11-12T18:00")));
    assertEquals(
        new BigDecimal("2"),
        WorkflowService.days(
            LocalDateTime.parse("2026-11-12T09:00"), LocalDateTime.parse("2026-11-13T18:00")));
  }

  @Test
  void invalidIntervalsAreRejected() {
    assertThrows(
        ApiException.class,
        () ->
            WorkflowService.days(
                LocalDateTime.parse("2026-11-12T09:01"), LocalDateTime.parse("2026-11-12T13:00")));
    assertThrows(
        ApiException.class,
        () ->
            WorkflowService.days(
                LocalDateTime.parse("2026-11-12T18:00"), LocalDateTime.parse("2026-11-12T09:00")));
    assertThrows(
        ApiException.class,
        () ->
            WorkflowService.days(
                LocalDateTime.parse("2026-12-31T09:00"), LocalDateTime.parse("2027-01-01T18:00")));
  }

  @Test
  void spreadsheetFormulasAreEscaped() {
    assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", CsvCells.cell("=HYPERLINK(\"x\")"));
    assertEquals("\"'=1\n+2\"", CsvCells.cell("=1\n+2"));
    assertEquals("\"审批,意见\"", CsvCells.cell("审批,意见"));
    assertEquals("\"'  +123\"", CsvCells.cell("  +123"));
  }

  @Test
  void ipChecksDoNotResolveArbitraryHostNames() {
    assertFalse(AdminController.validIp("example.com"));
    assertFalse(AdminController.validIp("999.1.1.1"));
    assertFalse(AdminController.validIp(null));
    assertTrue(AdminController.validIp("203.0.113.12"));
    assertTrue(AdminController.validIp("::1"));
  }
}
