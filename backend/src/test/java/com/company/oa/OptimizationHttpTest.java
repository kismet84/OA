package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.*;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/oa_opt_test",
      "oa.fcm-credentials=",
      "spring.data.redis.database=12"
    })
@RecordApplicationEvents
class OptimizationHttpTest {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate db;
  @Autowired Auth auth;
  @Autowired ApprovalUrgeService urges;
  @Autowired ApplicationEvents events;
  @MockitoBean Clock clock;
  String prefix, at, ot, mt, nt;
  long dept, admin, owner, manager, next;

  @BeforeEach
  void setup() {
    auth.redis.delete("login-ip:127.0.0.1");
    var fixed = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), AttendanceService.ZONE);
    when(clock.instant()).thenReturn(fixed.instant());
    when(clock.getZone()).thenReturn(AttendanceService.ZONE);
    doAnswer(i -> fixed.withZone(i.getArgument(0))).when(clock).withZone(any());
    prefix = "opt" + UUID.randomUUID().toString().substring(0, 8);
    dept =
        db.queryForObject(
            "INSERT INTO departments(name) VALUES(?) RETURNING id", Long.class, prefix);
    admin = user("admin", "ADMIN,FINANCE");
    manager = user("manager", "SUPERVISOR");
    next = user("next", "EMPLOYEE");
    owner = user("owner", "EMPLOYEE");
    db.update("UPDATE users SET manager_id=? WHERE id=?", manager, owner);
    at = login("admin");
    ot = login("owner");
    mt = login("manager");
    nt = login("next");
    db.update("UPDATE attendance_rules SET effective_from='2026-01-01',enabled=true WHERE id=1");
    db.update(
        "UPDATE workflow_config SET"
            + " auto_urge_enabled=true,auto_urge_hours=24,auto_urge_repeat_hours=0,urge_cooldown_minutes=120"
            + " WHERE id=1");
  }

  long user(String suffix, String roles) {
    return db.queryForObject(
        "INSERT INTO"
            + " users(employee_no,name,department_id,password_hash,roles,must_change_password,created_at)"
            + " VALUES(?,?,?,?,?,false,'2026-01-01') RETURNING id",
        Long.class,
        prefix + suffix,
        prefix + suffix,
        dept,
        auth.encoder.encode("Optimize@Test2026"),
        roles);
  }

  ResponseEntity<Map> call(String method, String path, String token, Object data) {
    var h = new HttpHeaders();
    h.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) h.setBearerAuth(token);
    return http.exchange(
        "/api" + path, HttpMethod.valueOf(method), new HttpEntity<>(data, h), Map.class);
  }

  Map ok(String method, String path, String token, Object data) {
    var r = call(method, path, token, data);
    assertEquals(200, r.getStatusCode().value(), r.toString());
    return r.getBody();
  }

  String login(String suffix) {
    return (String)
        ok(
                "POST",
                "/auth/login",
                null,
                Map.of("login", prefix + suffix, "password", "Optimize@Test2026"))
            .get("token");
  }

  List<Map> list(String path, String token) {
    var h = new HttpHeaders();
    h.setBearerAuth(token);
    var r = http.exchange("/api" + path, HttpMethod.GET, new HttpEntity<>(h), List.class);
    assertEquals(200, r.getStatusCode().value(), r.toString());
    return r.getBody();
  }

  long request() {
    return ((Number)
            ok(
                    "POST",
                    "/requests",
                    ot,
                    Map.of(
                        "kind",
                        "OUTING",
                        "reason",
                        "优化测试",
                        "startAt",
                        "2026-11-20T09:00:00",
                        "endAt",
                        "2026-11-20T13:00:00"))
                .get("id"))
        .longValue();
  }

  int count(String token, String key) {
    return ((Number) ok("GET", "/messages/summary", token, null).get(key)).intValue();
  }

  @Test
  void attendanceRulesCalendarScopeAndCsv() {
    assertEquals(403, call("GET", "/admin/attendance-rules", ot, null).getStatusCode().value());
    var rule = new HashMap<>(ok("GET", "/admin/attendance-rules", at, null));
    rule.put("workdays", "1,2,3,4,5");
    rule.put("late_grace_minutes", 241);
    assertEquals(400, call("PUT", "/admin/attendance-rules", at, rule).getStatusCode().value());
    rule.put("late_grace_minutes", 5);
    ok("PUT", "/admin/attendance-rules", at, rule);
    var batch =
        ok(
            "POST",
            "/admin/work-calendar/batch",
            at,
            Map.of("text", "2026-11-07,WORKDAY,补班\nbad,HOLIDAY,失败\n2026-11-02,HOLIDAY,节日"));
    assertEquals(2, batch.get("success"));
    assertEquals(1, batch.get("failed"));
    db.update(
        "INSERT INTO punches(user_id,punched_at,kind,simulated) VALUES(?,'2026-11-03"
            + " 09:06','NORMAL',true),(?,'2026-11-03 18:00','NORMAL',true)",
        owner,
        owner);
    var daily = list("/attendance/daily?userId=" + owner + "&from=2026-11-02&to=2026-11-03", ot);
    assertEquals("REST", daily.get(0).get("status"));
    assertEquals("LATE", daily.get(1).get("status"));
    assertEquals(
        403,
        httpListStatus("/attendance/daily?userId=" + next + "&from=2026-11-02&to=2026-11-03", ot));
    assertEquals(1, list("/attendance/summary?month=2026-11", ot).size());
    assertEquals(2, list("/attendance/summary?month=2026-11", mt).size());
  }

  int httpListStatus(String path, String token) {
    var h = new HttpHeaders();
    h.setBearerAuth(token);
    return http.exchange("/api" + path, HttpMethod.GET, new HttpEntity<>(h), String.class)
        .getStatusCode()
        .value();
  }

  @Test
  void exportPermissionsBomHeadersAndTotal() {
    for (String path : List.of("attendance-monthly.csv", "expenses-monthly.csv")) {
      var h = new HttpHeaders();
      h.setBearerAuth(at);
      var r =
          http.exchange(
              "/api/admin/exports/" + path + "?month=2026-11&departmentId=" + dept,
              HttpMethod.GET,
              new HttpEntity<>(h),
              byte[].class);
      assertEquals(200, r.getStatusCode().value());
      byte[] b = r.getBody();
      assertArrayEquals(new byte[] {(byte) 239, (byte) 187, (byte) 191}, Arrays.copyOf(b, 3));
      String text = new String(b, java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(text.contains("\r\n"));
      assertTrue(text.contains(path.startsWith("attendance") ? "缺卡次数" : "合计"));
    }
    assertEquals(403, httpListStatus("/admin/exports/expenses-monthly.csv?month=2026-11", ot));
    assertEquals("\"'=test\"", CsvCells.cell("=test"));
  }

  @Test
  void urgePermissionCooldownConcurrentPushAndReassign() throws Exception {
    long id = request();
    assertEquals(403, call("POST", "/requests/" + id + "/urge", mt, null).getStatusCode().value());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var jobs = new ArrayList<Future<Integer>>();
      for (int i = 0; i < 6; i++)
        jobs.add(
            pool.submit(
                () -> call("POST", "/requests/" + id + "/urge", ot, null).getStatusCode().value()));
      int success = 0;
      for (var job : jobs) {
        int status = job.get(10, TimeUnit.SECONDS);
        assertTrue(status == 200 || status == 429);
        if (status == 200) success++;
      }
      assertEquals(1, success);
    }
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM approval_urges WHERE business='REQUEST' AND target_id=?",
            Integer.class,
            id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE user_id=? AND request_id=? AND message LIKE"
                + " '【催办】%'",
            Integer.class, manager, id));
    assertEquals(true, ok("GET", "/requests/" + id, ot, null).get("canUrge"));
    assertNotNull(ok("GET", "/requests/" + id, ot, null).get("urgeAvailableAt"));
    ok("POST", "/requests/" + id + "/reassign", at, Map.of("approverId", next, "note", "交接"));
    ok("POST", "/requests/" + id + "/urge", ot, null);
    assertEquals(1, count(nt, "approvalsRequests"));
    assertEquals(0, count(mt, "approvalsRequests"));
  }

  @Test
  void automaticThresholdOnceReassignmentDisabledAndPushEvent() {
    long id = request();
    assertEquals(0, urges.auto());
    db.update(
        "UPDATE approval_steps SET activated_at=? WHERE request_id=? AND step=1",
        OffsetDateTime.parse("2026-11-12T12:00:00Z"),
        id);
    assertEquals(1, urges.auto());
    assertEquals(0, urges.auto());
    assertTrue(
        events.stream(NotificationService.Created.class)
            .anyMatch(e -> e.message().startsWith("【超时提醒】") && e.userId() == manager));
    ok("POST", "/requests/" + id + "/reassign", at, Map.of("approverId", next, "note", "重计时"));
    assertEquals(0, urges.auto());
    db.update(
        "UPDATE approval_steps SET activated_at=? WHERE request_id=? AND step=1",
        OffsetDateTime.parse("2026-11-12T12:00:00Z"),
        id);
    db.update("UPDATE workflow_config SET auto_urge_enabled=false WHERE id=1");
    assertEquals(0, urges.auto());
    db.update("UPDATE workflow_config SET auto_urge_enabled=true WHERE id=1");
    db.update("UPDATE users SET enabled=false WHERE id=?", next);
    assertEquals(0, urges.auto());
    db.update("UPDATE users SET enabled=true WHERE id=?", next);
    assertEquals(1, urges.auto());
    ok("POST", "/requests/" + id + "/cancel", ot, null);
    assertEquals(0, urges.auto());
  }

  @Test
  void templatesOwnershipWhitelistLimitsAndCopy() {
    var input =
        Map.of(
            "name",
            "常用外出",
            "kind",
            "OUTING",
            "payload",
            Map.of(
                "reason",
                "拜访",
                "startAt",
                "2026-11-20",
                "attachmentIds",
                List.of("secret"),
                "ccUserIds",
                List.of(next)));
    long id = ((Number) ok("POST", "/request-templates", ot, input).get("id")).longValue();
    var rows = list("/request-templates", ot);
    var payload = (Map) rows.getFirst().get("payload");
    assertFalse(payload.containsKey("startAt"));
    assertFalse(payload.containsKey("attachmentIds"));
    assertEquals(403, call("DELETE", "/request-templates/" + id, nt, null).getStatusCode().value());
    ok(
        "PUT",
        "/request-templates/" + id,
        ot,
        Map.of("name", "重命名", "kind", "OUTING", "payload", Map.of()));
    for (int i = 1; i < 30; i++)
      ok(
          "POST",
          "/request-templates",
          ot,
          Map.of("name", "模板" + i, "kind", "OUTING", "payload", Map.of()));
    assertEquals(
        400,
        call(
                "POST",
                "/request-templates",
                ot,
                Map.of("name", "超额", "kind", "OUTING", "payload", Map.of()))
            .getStatusCode()
            .value());
    ok("DELETE", "/request-templates/" + id, ot, null);
    long rid = request();
    var copy = ok("GET", "/requests/" + rid + "/copy", ot, null);
    var draft = (Map) copy.get("payload");
    assertEquals("OUTING", copy.get("kind"));
    for (String key :
        List.of("id", "startAt", "endAt", "attachmentId", "attachments", "status", "steps"))
      assertFalse(draft.containsKey(key));
    assertEquals(403, call("GET", "/requests/" + rid + "/copy", nt, null).getStatusCode().value());
  }

  @Test
  void splitCountsMatchInboxAndProxy() {
    long id = request();
    assertEquals(list("/requests?inbox=true", mt).size(), count(mt, "approvalsRequests"));
    assertEquals(1, count(mt, "approvalsRequests"));
    ok("POST", "/requests/" + id + "/reassign", at, Map.of("approverId", next, "note", "交接"));
    assertEquals(0, count(mt, "approvalsRequests"));
    assertEquals(1, count(nt, "approvalsRequests"));
    db.update("UPDATE users SET enabled=false WHERE id=?", next);
    ok("POST", "/requests/" + id + "/decision", at, Map.of("approve", true));
    db.update("UPDATE users SET enabled=true WHERE id=?", next);
    assertEquals(0, count(nt, "approvalsRequests"));
    var s = ok("GET", "/messages/summary", mt, null);
    assertEquals(
        ((Number) s.get("pending")).intValue(),
        List.of("approvalsRequests", "approvalsContracts", "approvalsAssets", "tasks").stream()
            .mapToInt(k -> ((Number) s.get(k)).intValue())
            .sum());
  }

  @Test
  void manualPublishesCreatedEventAndUrgeConfigIsAudited() {
    long id = request();
    urges.manual("REQUEST", id, new Actor(owner, prefix + "owner", "EMPLOYEE", false, 0));
    assertTrue(
        events.stream(NotificationService.Created.class)
            .anyMatch(
                e ->
                    e.userId() == manager
                        && e.message().startsWith("【催办】")
                        && e.data().get("request_id").equals(String.valueOf(id))));
    ok(
        "PUT",
        "/admin/urge-config",
        at,
        Map.of(
            "urge_cooldown_minutes",
            60,
            "auto_urge_enabled",
            true,
            "auto_urge_hours",
            12,
            "auto_urge_repeat_hours",
            48));
    assertEquals(60, ok("GET", "/admin/urge-config", at, null).get("urge_cooldown_minutes"));
    assertEquals(403, call("GET", "/admin/urge-config", ot, null).getStatusCode().value());
    assertEquals(
        400,
        call(
                "PUT",
                "/admin/urge-config",
                at,
                Map.of(
                    "urge_cooldown_minutes", 0, "auto_urge_hours", 0, "auto_urge_repeat_hours", 0))
            .getStatusCode()
            .value());
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE actor_id=? AND action='SAVE_URGE_CONFIG'",
            Integer.class,
            admin));
  }

  @Test
  void contractRoleFinalAssetCountsUrgesAndReassignment() {
    long contract =
        ((Number)
                ok(
                        "POST",
                        "/contracts",
                        ot,
                        Map.of(
                            "name",
                            prefix,
                            "type",
                            "SERVICE",
                            "counterparty",
                            "验收",
                            "amount",
                            1,
                            "signedDate",
                            "2026-11-01",
                            "effectiveDate",
                            "2026-11-01"))
                    .get("id"))
            .longValue();
    ok("POST", "/contracts/" + contract + "/submit", ot, null);
    assertEquals(true, ok("GET", "/contracts/" + contract, ot, null).get("canUrge"));
    ok("POST", "/contracts/" + contract + "/urge", ot, null);
    assertEquals(1, count(mt, "approvalsContracts"));
    assertEquals(list("/contracts?scope=pending", mt).size(), count(mt, "approvalsContracts"));
    ok("POST", "/contracts/" + contract + "/approve", mt, Map.of("approved", true));
    assertEquals(0, count(mt, "approvalsContracts"));
    assertTrue(count(at, "approvalsContracts") >= 1);
    assertTrue(
        ((List) ok("POST", "/contracts/" + contract + "/urge", ot, null).get("urgedUserIds"))
            .contains((int) admin));
    ok("POST", "/contracts/" + contract + "/proxy-approve", at, Map.of("approved", true));
    assertEquals(false, ok("GET", "/contracts/" + contract, ot, null).get("canUrge"));
    long category =
        db.queryForObject(
            "INSERT INTO asset_categories(name,kind) VALUES(?,'ITEM') RETURNING id",
            Long.class,
            prefix);
    long asset =
        ((Number)
                ok(
                        "POST",
                        "/assets/requests",
                        ot,
                        Map.of(
                            "categoryId",
                            category,
                            "quantity",
                            1,
                            "purpose",
                            "验收",
                            "expectedDate",
                            "2026-11-20"))
                    .get("id"))
            .longValue();
    assertEquals(list("/assets/requests?scope=pending", mt).size(), count(mt, "approvalsAssets"));
    ok("POST", "/assets/requests/" + asset + "/urge", ot, null);
    assertEquals(
        429, call("POST", "/assets/requests/" + asset + "/urge", ot, null).getStatusCode().value());
    ok(
        "POST",
        "/assets/requests/" + asset + "/reassign",
        at,
        Map.of("approverId", next, "note", "交接"));
    assertEquals(0, count(mt, "approvalsAssets"));
    assertEquals(1, count(nt, "approvalsAssets"));
    ok("POST", "/assets/requests/" + asset + "/urge", ot, null);
    db.update("UPDATE users SET enabled=false WHERE id=?", next);
    assertEquals(
        400, call("POST", "/assets/requests/" + asset + "/urge", ot, null).getStatusCode().value());
    ok("POST", "/assets/requests/" + asset + "/proxy-approve", at, Map.of("approved", true));
    db.update("UPDATE users SET enabled=true WHERE id=?", next);
    assertEquals(0, count(nt, "approvalsAssets"));
  }

  Map<String, Object> userInput(String suffix, boolean enabled) {
    return new HashMap<>(
        Map.of(
            "employeeNo",
            prefix + suffix,
            "name",
            prefix + suffix,
            "departmentId",
            dept,
            "enabled",
            enabled,
            "roles",
            List.of("EMPLOYEE")));
  }

  String attendanceCsv(String month, boolean detail) {
    var h = new HttpHeaders();
    h.setBearerAuth(at);
    var result =
        http.exchange(
            "/api/admin/exports/attendance-monthly.csv?month="
                + month
                + "&departmentId="
                + dept
                + "&detail="
                + detail,
            HttpMethod.GET,
            new HttpEntity<>(h),
            String.class);
    assertEquals(200, result.getStatusCode().value());
    return result.getBody();
  }

  @Test
  void disabledHistoryDailySummaryCsvWorkbenchAndReactivation() {
    var input = userInput("owner", false);
    ok("PUT", "/admin/users/" + owner, at, input);
    var disabled =
        db.queryForObject("SELECT disabled_at FROM users WHERE id=?", OffsetDateTime.class, owner);
    assertNotNull(disabled);
    ok("PUT", "/admin/users/" + owner, at, input);
    assertEquals(
        disabled,
        db.queryForObject("SELECT disabled_at FROM users WHERE id=?", OffsetDateTime.class, owner));
    var date = disabled.atZoneSameInstant(AttendanceService.ZONE).toLocalDate();
    var before = date.minusDays(1);
    var fixed =
        Clock.fixed(
            date.plusDays(2).atStartOfDay(AttendanceService.ZONE).toInstant(),
            AttendanceService.ZONE);
    when(clock.instant()).thenReturn(fixed.instant());
    doAnswer(i -> fixed.withZone(i.getArgument(0))).when(clock).withZone(any());
    db.update(
        "UPDATE users SET created_at=? WHERE id=?", before.minusDays(10).atStartOfDay(), owner);
    db.update(
        "UPDATE attendance_rules SET"
            + " effective_from=?,work_start='09:00',work_end='18:00',late_grace_minutes=5,early_grace_minutes=5"
            + " WHERE id=1",
        before.minusDays(10));
    for (var day : List.of(before, date, date.plusDays(1)))
      db.update(
          "INSERT INTO work_calendar(day,kind) VALUES(?,'WORKDAY') ON CONFLICT(day)"
              + " DO UPDATE SET kind='WORKDAY'",
          day);
    db.update(
        "INSERT INTO punches(user_id,punched_at,kind,simulated)"
            + " VALUES(?,?,'NORMAL',true),(?,?,'NORMAL',true)",
        owner,
        before.atTime(9, 6),
        owner,
        before.atTime(18, 0));
    String range = "&from=" + before + "&to=" + date.plusDays(1);
    var daily = list("/attendance/daily?userId=" + owner + range, at);
    assertEquals(
        List.of("LATE", "EXCLUDED", "EXCLUDED"), daily.stream().map(r -> r.get("status")).toList());
    var summary =
        list("/attendance/summary?month=" + YearMonth.from(before), at).stream()
            .filter(r -> ((Number) r.get("user_id")).longValue() == owner)
            .findFirst()
            .orElseThrow();
    assertEquals(1, summary.get("lateCount"));
    String detail = attendanceCsv(YearMonth.from(before).toString(), true);
    assertTrue(
        detail
            .lines()
            .anyMatch(
                l ->
                    l.contains(prefix + "owner")
                        && l.contains(before.toString())
                        && l.contains("迟到")));
    String excluded = attendanceCsv(YearMonth.from(date).toString(), true);
    assertTrue(
        excluded
            .lines()
            .anyMatch(
                l ->
                    l.contains(prefix + "owner")
                        && l.contains(date.toString())
                        && l.contains("未纳入")));
    var csvRow =
        attendanceCsv(YearMonth.from(before).toString(), false)
            .lines()
            .filter(l -> l.contains(prefix + "owner"))
            .findFirst()
            .orElseThrow()
            .split(",");
    assertEquals("\"1\"", csvRow[5]); // 迟到次数
    assertTrue(((Number) summary.get("expectedDays")).intValue() > 0);
    var workbench =
        ok("GET", "/workbench/summary?scope=team&departmentId=" + dept + range, at, null);
    var anomalies = (Map) ((Map) workbench.get("attendance")).get("anomalies");
    assertEquals(1, anomalies.get("lateCount"));
    assertEquals(3, workbench.get("people"));
    db.update("UPDATE users SET enabled=false,disabled_at=NULL WHERE id=?", next);
    assertEquals(
        "EXCLUDED", list("/attendance/daily?userId=" + next + range, at).getFirst().get("status"));
    var legacySummary =
        list("/attendance/summary?month=" + YearMonth.from(before), at).stream()
            .filter(r -> ((Number) r.get("user_id")).longValue() == next)
            .findFirst()
            .orElseThrow();
    assertEquals(0, legacySummary.get("expectedDays"));
    assertEquals(0, legacySummary.get("missingCount"));
    assertTrue(
        attendanceCsv(YearMonth.from(before).toString(), true)
            .lines()
            .anyMatch(l -> l.contains(prefix + "next") && l.contains(before.toString()) && l.contains("未纳入")));
    input.put("enabled", true);
    ok("PUT", "/admin/users/" + owner, at, input);
    assertNull(
        db.queryForObject("SELECT disabled_at FROM users WHERE id=?", OffsetDateTime.class, owner));
    assertEquals(
        "MISSING", list("/attendance/daily?userId=" + owner + range, at).get(1).get("status"));
    var create = userInput("inactive", false);
    create.put("password", "Optimize@Test2026");
    long id = ((Number) ok("POST", "/admin/users", at, create).get("id")).longValue();
    assertNotNull(
        db.queryForObject("SELECT disabled_at FROM users WHERE id=?", OffsetDateTime.class, id));
    ok("PUT", "/admin/users/" + next, at, userInput("next", false));
    assertNull(
        db.queryForObject("SELECT disabled_at FROM users WHERE id=?", OffsetDateTime.class, next));
  }

  @Test
  void automaticRespectsManualCooldownAndNewActivationCycle() {
    long id = request();
    var now = Instant.parse("2026-11-13T12:00:00Z");
    db.update(
        "UPDATE approval_steps SET activated_at=? WHERE request_id=? AND step=1",
        OffsetDateTime.ofInstant(now.minusSeconds(86400), ZoneOffset.UTC),
        id);
    ok("POST", "/requests/" + id + "/urge", ot, null);
    assertEquals(0, urges.autoForDev(now, id));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM approval_urges WHERE target_id=? AND business='REQUEST' AND"
                + " mode='AUTO'",
            Integer.class,
            id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE request_id=? AND message LIKE '【催办】%'",
            Integer.class, id));
    assertEquals(
        0,
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE request_id=? AND message LIKE '【超时提醒】%'",
            Integer.class, id));
    assertEquals(0, urges.autoForDev(now.plusSeconds(7199), id));
    assertEquals(1, urges.autoForDev(now.plusSeconds(7200), id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM approval_urges WHERE target_id=? AND business='REQUEST' AND"
                + " mode='AUTO'",
            Integer.class,
            id));
    assertEquals(
        1,
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE request_id=? AND message LIKE '【超时提醒】%'",
            Integer.class, id));
    assertEquals(0, urges.autoForDev(now.plusSeconds(7201), id));
    ok("POST", "/requests/" + id + "/cancel", ot, null);

    long reassigned = request();
    db.update(
        "UPDATE approval_steps SET activated_at=? WHERE request_id=? AND step=1",
        OffsetDateTime.ofInstant(now.minusSeconds(86400), ZoneOffset.UTC),
        reassigned);
    ok("POST", "/requests/" + reassigned + "/urge", ot, null);
    ok(
        "POST",
        "/requests/" + reassigned + "/reassign",
        at,
        Map.of("approverId", next, "note", "交接"));
    ok(
        "POST",
        "/requests/" + reassigned + "/reassign",
        at,
        Map.of("approverId", manager, "note", "重新激活"));
    db.update(
        "UPDATE approval_steps SET activated_at=? WHERE request_id=? AND step=1",
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
        reassigned);
    db.update("UPDATE workflow_config SET auto_urge_hours=1 WHERE id=1");
    assertEquals(1, urges.autoForDev(now.plusSeconds(3600), reassigned));
    ok("POST", "/requests/" + reassigned + "/cancel", ot, null);
  }
}
