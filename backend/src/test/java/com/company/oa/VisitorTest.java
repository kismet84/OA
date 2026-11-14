package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class VisitorTest {
  final JdbcTemplate db=mock(JdbcTemplate.class);
  final VisitorController controller=new VisitorController(db,mock(Auth.class),Clock.fixed(Instant.parse("2032-01-01T00:00:00Z"),ZoneOffset.UTC));
  @Test void retriesOccupiedCodesAndReleasesOnlyExpiredSlots(){
    when(db.queryForObject("SELECT count(*) FROM visitors WHERE active_code=?",Long.class,"000001")).thenReturn(1L);
    when(db.queryForObject("SELECT count(*) FROM visitors WHERE active_code=?",Long.class,"000002")).thenReturn(0L);
    var n=new AtomicInteger();
    assertEquals("000002",controller.allocate(controller.now(),n::incrementAndGet));
    verify(db).update("UPDATE visitors SET active_code=NULL WHERE status='WAITING' AND end_at<? AND active_code IS NOT NULL",LocalDateTime.parse("2032-01-01T08:00:00"));
    assertEquals(2,n.get());
  }
  @Test void exhaustedRetriesReturnChinese409(){
    when(db.queryForObject("SELECT count(*) FROM visitors WHERE active_code=?",Long.class,"000001")).thenReturn(1L);
    var error=assertThrows(ApiException.class,()->controller.allocate(controller.now(),()->1));
    assertEquals(409,error.status);assertTrue(error.getMessage().contains("访客码冲突"));
    verify(db,times(100)).queryForObject("SELECT count(*) FROM visitors WHERE active_code=?",Long.class,"000001");
  }
  @Test void exportEscapesQuotesAndSpreadsheetFormulas(){
    assertEquals("\"'=SUM(1)\"",CsvCells.cell("=SUM(1)"));
    assertEquals("\"单位\"\"名称\"",CsvCells.cell("单位\"名称"));
  }
}
