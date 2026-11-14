package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real PostgreSQL/Redis/HTTP regression, isolated from development business records. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/oa_review_test",
      "oa.fcm-credentials=",
      "spring.data.redis.database=13"
    })
class ReviewFixesHttpTest {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate db;
  @Autowired Auth auth;
  @MockitoBean DocumentMedia media;
  long department, admin, owner, old, next, head, contract;
  String at, ot, mt, nt;
  String prefix;

  @BeforeEach
  void setup() {
    auth.redis.delete("login-ip:127.0.0.1");
    prefix = "review" + UUID.randomUUID().toString().substring(0, 8);
    department =
        db.queryForObject(
            "INSERT INTO departments(name) VALUES(?) RETURNING id", Long.class, prefix);
    admin = user("admin", "ADMIN");
    old = user("old", "SUPERVISOR");
    next = user("new", "EMPLOYEE");
    head = user("head", "DEPT_HEAD");
    owner = user("owner", "EMPLOYEE");
    db.update("UPDATE users SET manager_id=? WHERE id=?", old, owner);
    db.update("UPDATE departments SET head_id=? WHERE id=?", head, department);
    at = login("admin");
    ot = login("owner");
    mt = login("old");
    nt = login("new");
    db.update(
        "INSERT INTO networks(kind,value) SELECT 'IP','127.0.0.1' WHERE NOT EXISTS(SELECT 1 FROM"
            + " networks WHERE kind='IP' AND value='127.0.0.1')");
    contract =
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
                            prefix,
                            "amount",
                            1,
                            "signedDate",
                            "2032-01-01",
                            "effectiveDate",
                            "2032-01-01"))
                    .get("id"))
            .longValue();
    ok("POST", "/contracts/" + contract + "/submit", ot, null);
  }

  long user(String suffix, String role) {
    return db.queryForObject(
        "INSERT INTO users(employee_no,name,department_id,password_hash,roles,must_change_password)"
            + " VALUES(?,?,?,?,?,false) RETURNING id",
        Long.class,
        prefix + suffix,
        prefix + suffix,
        department,
        auth.encoder.encode("Review@Test2026"),
        role);
  }

  String login(String suffix) {
    return (String)
        ok(
                "POST",
                "/auth/login",
                null,
                Map.of("login", prefix + suffix, "password", "Review@Test2026"))
            .get("token");
  }

  ResponseEntity<Map> call(String method, String path, String token, Object body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(
        "/api" + path, HttpMethod.valueOf(method), new HttpEntity<>(body, headers), Map.class);
  }

  Map ok(String method, String path, String token, Object body) {
    var r = call(method, path, token, body);
    assertEquals(200, r.getStatusCode().value(), r.toString());
    return r.getBody();
  }

  void status(int expected, String method, String path, String token, Object body) {
    assertEquals(expected, call(method, path, token, body).getStatusCode().value());
  }

  Map disable(long user, String suffix, String role) {
    return Map.of(
        "employeeNo",
        prefix + suffix,
        "name",
        prefix + suffix,
        "departmentId",
        department,
        "enabled",
        false,
        "roles",
        List.of(role));
  }

  Map assignment(long user) {
    return Map.of("approverId", user, "note", "人员交接");
  }

  @Test
  void contractSnapshotReassignProxyAndDisableGuards() {
    // Organization changes cannot transfer ownership of an already submitted step.
    db.update("UPDATE users SET manager_id=? WHERE id=?", head, owner);
    assertEquals(true, ok("GET", "/contracts/" + contract, mt, null).get("canApprove"));
    assertTrue(contains("/contracts?scope=pending", mt, contract));
    assertTrue(contains("/messages?category=pending", mt, contract));
    assertEquals(false, ok("GET", "/contracts/" + contract, login("head"), null).get("canApprove"));
    var denied = call("PUT", "/admin/users/" + old, at, disable(old, "old", "SUPERVISOR"));
    assertEquals(400, denied.getStatusCode().value());
    assertTrue(denied.getBody().get("message").toString().contains("在途合同"));
    status(403, "POST", "/contracts/" + contract + "/reassign", mt, assignment(next));
    status(400, "POST", "/contracts/" + contract + "/reassign", at, assignment(owner));
    db.update("UPDATE users SET enabled=false WHERE id=?", next);
    status(400, "POST", "/contracts/" + contract + "/reassign", at, assignment(next));
    db.update("UPDATE users SET enabled=true WHERE id=?", next);
    ok("POST", "/contracts/" + contract + "/reassign", at, assignment(next));
    assertEquals(false, ok("GET", "/contracts/" + contract, mt, null).get("canApprove"));
    assertFalse(contains("/contracts?scope=pending", mt, contract));
    assertFalse(contains("/messages?category=pending", mt, contract));
    assertTrue(contains("/contracts?scope=pending", nt, contract));
    assertTrue(contains("/messages?category=pending", nt, contract));
    assertEquals(true, ok("GET", "/contracts/" + contract, nt, null).get("canApprove"));
    status(403, "POST", "/contracts/" + contract + "/approve", mt, Map.of("approved", true));
    ok("PUT", "/admin/users/" + old, at, disable(old, "old", "SUPERVISOR"));
    ok("POST", "/contracts/" + contract + "/approve", nt, Map.of("approved", true));
    ok(
        "POST",
        "/contracts/" + contract + "/proxy-approve",
        at,
        Map.of("approved", false, "note", "代批驳回"));
    var step =
        db.queryForMap("SELECT * FROM contract_steps WHERE contract_id=? AND step=2", contract);
    assertEquals(admin, ((Number) step.get("actor_id")).longValue());
    assertTrue(step.get("note").toString().contains("管理员代批"));
    assertEquals("REJECTED", ok("GET", "/contracts/" + contract, nt, null).get("workflow_status"));
  }

  @Test
  void futureContractStepBlocksDisableAndFormerDepartmentHeadKeepsSnapshot() {
    long id =
        ((Number)
                ok(
                        "POST",
                        "/contracts",
                        ot,
                        Map.of(
                            "name",
                            prefix + "large",
                            "type",
                            "SERVICE",
                            "counterparty",
                            prefix,
                            "amount",
                            60000,
                            "signedDate",
                            "2032-01-01",
                            "effectiveDate",
                            "2032-01-01"))
                    .get("id"))
            .longValue();
    ok("POST", "/contracts/" + id + "/submit", ot, null);
    db.update("UPDATE departments SET head_id=null WHERE id=?", department);
    var denied = call("PUT", "/admin/users/" + head, at, disable(head, "head", "DEPT_HEAD"));
    assertEquals(400, denied.getStatusCode().value());
    assertTrue(denied.getBody().get("message").toString().contains("在途合同"));
    db.update("UPDATE users SET roles='EMPLOYEE' WHERE id=?", head);
    String formerHead = login("head");
    ok("GET", "/contracts/" + id, formerHead, null);
    ok("POST", "/contracts/" + id + "/approve", mt, Map.of("approved", true));
    assertEquals(true, ok("GET", "/contracts/" + id, formerHead, null).get("canApprove"));
    ok("POST", "/contracts/" + id + "/approve", formerHead, Map.of("approved", true));
    ok("PUT", "/admin/users/" + head, at, disable(head, "head", "EMPLOYEE"));
  }

  @Test
  void generalWorkflowReassignAndProxyRemainAvailable() {
    var input =
        Map.of(
            "kind",
            "OUTING",
            "reason",
            "交接测试",
            "startAt",
            "2032-05-01T09:00:00",
            "endAt",
            "2032-05-01T13:00:00");
    long id = ((Number) ok("POST", "/requests", ot, input).get("id")).longValue();
    ok("POST", "/requests/" + id + "/reassign", at, assignment(next));
    status(403, "POST", "/requests/" + id + "/decision", mt, Map.of("approve", true));
    ok("POST", "/requests/" + id + "/decision", nt, Map.of("approve", true));
    ok("GET", "/requests/" + id, mt, null); // Former assignee retains history access.
    var second = new HashMap<String, Object>(input);
    second.put("startAt", "2032-05-02T09:00:00");
    second.put("endAt", "2032-05-02T13:00:00");
    long proxy = ((Number) ok("POST", "/requests", ot, second).get("id")).longValue();
    db.update("UPDATE users SET enabled=false WHERE id=?", old);
    ok(
        "POST",
        "/requests/" + proxy + "/decision",
        at,
        Map.of("approve", false, "comment", "停用员工交接"));
    var step = db.queryForMap("SELECT * FROM approval_steps WHERE request_id=? AND step=1", proxy);
    assertEquals(admin, ((Number) step.get("actor_id")).longValue());
    assertTrue(step.get("comment").toString().contains("管理员代批"));
  }

  java.util.List<Map> list(String path, String token) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    var response =
        http.exchange(
            "/api" + path, HttpMethod.GET, new HttpEntity<>(headers), java.util.List.class);
    assertEquals(200, response.getStatusCode().value());
    return response.getBody();
  }

  boolean contains(String path, String token, long id) {
    return list(path, token).stream().anyMatch(r -> ((Number) r.get("id")).longValue() == id);
  }

  @Test
  void assetReassignAndProxyMaintainAuditAndDisableGuard() {
    long category =
        db.queryForObject(
            "INSERT INTO asset_categories(name,kind) VALUES(?,'ITEM') RETURNING id",
            Long.class,
            prefix);
    long id =
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
                            "测试",
                            "expectedDate",
                            "2032-01-01"))
                    .get("id"))
            .longValue();
    db.update("UPDATE users SET manager_id=null WHERE id=?", owner);
    ok("POST", "/contracts/" + contract + "/reassign", at, assignment(next));
    var denied = call("PUT", "/admin/users/" + old, at, disable(old, "old", "SUPERVISOR"));
    assertEquals(400, denied.getStatusCode().value());
    assertTrue(denied.getBody().get("message").toString().contains("资产领用"));
    ok("POST", "/assets/requests/" + id + "/reassign", at, assignment(next));
    assertEquals(false, ok("GET", "/assets/requests/" + id, mt, null).get("canApprove"));
    assertEquals(true, ok("GET", "/assets/requests/" + id, nt, null).get("canApprove"));
    status(403, "POST", "/assets/requests/" + id + "/proxy-approve", nt, Map.of("approved", true));
    ok(
        "POST",
        "/assets/requests/" + id + "/proxy-approve",
        at,
        Map.of("approved", true, "note", "交接代办"));
    var r = ok("GET", "/assets/requests/" + id, ot, null);
    assertEquals("READY", r.get("status"));
    assertEquals(admin, ((Number) r.get("approval_actor_id")).longValue());
    assertTrue(r.get("approval_note").toString().contains("管理员代批"));
  }

  Map<String, Object> punch(String source) {
    var r =
        new HashMap<String, Object>(
            Map.of("kind", "NORMAL", "latitude", 31.23, "longitude", 121.47, "accuracy", 12));
    if (source != null) {
      r.put("locationSource", source);
      r.put("locatedAt", Instant.now().minusSeconds(60).toString());
    }
    return r;
  }

  @Test
  void provenancePersistsAndLegacyIsRejected() {
    for (String source : List.of("LIVE", "WARMUP_CACHE", "LAST_KNOWN", "STALE_FALLBACK")) {
      long id = ((Number) ok("POST", "/punches", ot, punch(source)).get("id")).longValue();
      var r = db.queryForMap("SELECT * FROM punches WHERE id=?", id);
      assertEquals(source, r.get("location_source"));
      assertNotNull(r.get("located_at"));
    }
    var visible = list("/punches", ot).getFirst();
    assertNotNull(visible.get("locatedAt"));
    assertEquals(visible.get("location_source"), visible.get("locationSource"));
    assertTrue(visible.get("locatedAt").toString().matches(".*[TZ+].*"));
    status(400, "POST", "/punches", ot, punch(null));
    status(400, "POST", "/punches", ot, punch("UNKNOWN"));
    status(400, "POST", "/punches", ot, punch("FAKE"));
    var missing = punch("LIVE");
    missing.remove("locatedAt");
    status(400, "POST", "/punches", ot, missing);
    var stale = punch("LAST_KNOWN");
    stale.put("locatedAt", Instant.now().minusSeconds(3600).toString());
    status(400, "POST", "/punches", ot, stale);
  }

  @Test
  void punchPrecisionFreshnessAndUpgradeErrors() {
    for (String source : List.of("LIVE", "WARMUP_CACHE", "LAST_KNOWN", "STALE_FALLBACK")) {
      var valid = punch(source);
      valid.put("accuracy", 200);
      valid.put("locatedAt", Instant.now().minusSeconds(240).toString());
      ok("POST", "/punches", ot, valid);
      for (long seconds : new long[] {301, 1800, 86400, -121}) {
        valid.put("locatedAt", Instant.now().minusSeconds(seconds).toString());
        var denied = call("POST", "/punches", ot, valid);
        assertEquals(400, denied.getStatusCode().value());
        assertTrue(denied.getBody().get("message").toString().contains("5分钟"));
      }
    }
    var inaccurate = punch("LIVE");
    inaccurate.put("accuracy", 201);
    status(400, "POST", "/punches", ot, inaccurate);
    for (String missing : List.of("locatedAt", "locationSource", "UNKNOWN")) {
      var legacy = punch("LIVE");
      if (missing.equals("UNKNOWN")) legacy.put("locationSource", "UNKNOWN");
      else legacy.remove(missing);
      var denied = call("POST", "/punches", ot, legacy);
      assertEquals(400, denied.getStatusCode().value());
      assertTrue(denied.getBody().get("message").toString().contains("升级 App 或改用员工网页"));
    }
    var field = punch("LIVE");
    field.put("kind", "FIELD");
    field.put("locatedAt", Instant.now().minusSeconds(301).toString());
    var denied = call("POST", "/punches", ot, field);
    assertEquals(400, denied.getStatusCode().value());
    assertTrue(denied.getBody().get("message").toString().contains("5分钟"));
  }

  @Test
  void sameUserSerializesOtherUsersAndBusinessesDoNotBlock() throws Exception {
    try (var connection = db.getDataSource().getConnection();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      connection.setAutoCommit(false);
      try (var stmt =
          connection.prepareStatement("SELECT pg_advisory_xact_lock(20261112,hashint8(?))")) {
        stmt.setLong(1, owner);
        stmt.execute();
      }
      var same = executor.submit(() -> ok("POST", "/punches", ot, punch("LIVE")));
      var other = executor.submit(() -> ok("POST", "/punches", nt, punch("LIVE")));
      other.get(5, TimeUnit.SECONDS);
      assertThrows(TimeoutException.class, () -> same.get(300, TimeUnit.MILLISECONDS));
      connection.rollback();
      same.get(5, TimeUnit.SECONDS);
      connection.createStatement().execute("SELECT pg_advisory_xact_lock(20261114)");
      executor.submit(() -> ok("POST", "/punches", ot, punch("LIVE"))).get(5, TimeUnit.SECONDS);
      connection.rollback();
      var futures = new ArrayList<Future<Map>>();
      for (int i = 0; i < 8; i++)
        futures.add(
            executor.submit(
                () ->
                    ok(
                        "POST",
                        "/contracts/" + contract + "/proxy-approve",
                        at,
                        Map.of("approved", true))));
      // Only two current steps can be approved. Extra stale operations must fail without duplicate
      // transitions.
      int passed = 0;
      for (var f : futures) {
        try {
          f.get(10, TimeUnit.SECONDS);
          passed++;
        } catch (ExecutionException e) {
          assertInstanceOf(AssertionError.class, e.getCause());
        }
      }
      assertEquals(2, passed);
      assertEquals(
          2,
          db.queryForObject(
              "SELECT count(*) FROM contract_steps WHERE contract_id=? AND status='APPROVED'",
              Integer.class,
              contract));
    }
  }

  @Test
  void documentParsingRunsBeforeTransactionAndFailureCreatesNothing() throws Exception {
    UUID file = UUID.randomUUID();
    db.update(
        "INSERT INTO attachments(id,user_id,original_name,media_type)"
            + " VALUES(?,?,?,'application/pdf')",
        file,
        admin,
        "review.pdf");
    when(media.pages(any(), eq("application/pdf")))
        .thenAnswer(
            inv -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return 1;
            });
    var input =
        Map.of("title", prefix, "category", "FILE", "attachmentIds", List.of(file.toString()));
    long id = ((Number) ok("POST", "/documents", at, input).get("id")).longValue();
    ok("PUT", "/documents/" + id, at, input);
    ok("POST", "/documents/" + id + "/withdraw", at, null);
    ok("POST", "/documents/" + id + "/republish", at, null);
    ok("GET", "/documents/" + id + "/attachments/" + file + "/preview", at, null);
    when(media.preview(any(), anyString(), anyInt(), anyString()))
        .thenAnswer(
            inv -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return new byte[] {1};
            });
    when(media.download(any(), anyString(), anyInt(), anyString()))
        .thenAnswer(
            inv -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              return new byte[] {1};
            });
    var headers = new HttpHeaders();
    headers.setBearerAuth(at);
    for (String suffix : List.of("/pages/1", "/download")) {
      var response =
          http.exchange(
              "/api/documents/" + id + "/attachments/" + file + suffix,
              HttpMethod.GET,
              new HttpEntity<>(headers),
              byte[].class);
      assertEquals(200, response.getStatusCode().value());
    }
    verify(media, times(6)).pages(any(), anyString());
    when(media.pages(any(), anyString())).thenThrow(new ApiException(400, "无效PDF"));
    status(400, "POST", "/documents", at, input);
    assertEquals(
        1,
        db.queryForObject("SELECT count(*) FROM documents WHERE title=?", Integer.class, prefix));
  }
}
