package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real database/Redis/HTTP tests; dedicated database and Redis logical database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/oa_batch1_test",
    "spring.data.redis.database=14", "oa.fcm-credentials="})
class RemediationBatch1HttpTest {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate db;
  @Autowired Auth auth;
  @Autowired Environment env;
  @Autowired ObjectMapper json;
  MockMvc loginMvc;
  String prefix, password, financeToken, adminToken, employeeToken, managerToken;
  long dept, finance, admin, owner, manager;
  Set<String> initialKeys;

  @BeforeEach void setup() {
    initialKeys = new HashSet<>(auth.redis.keys("*"));
    prefix = "batch" + UUID.randomUUID().toString().substring(0, 8);
    password = UUID.randomUUID().toString();
    dept = db.queryForObject("INSERT INTO departments(name) VALUES(?) RETURNING id", Long.class, prefix);
    finance = user("finance", "FINANCE");
    admin = user("admin", "ADMIN");
    owner = user("owner", "EMPLOYEE");
    manager = user("manager", "SUPERVISOR");
    loginMvc = MockMvcBuilders.standaloneSetup(
        new AuthController(auth, db, env, new ClientIpResolver("127.0.0.1")))
        .setControllerAdvice(new ApiErrors()).build();
    financeToken = login("finance");
    adminToken = login("admin");
    employeeToken = login("owner");
    managerToken = login("manager");
  }

  @AfterEach void clearKeys() {
    var keys = new HashSet<>(auth.redis.keys("*"));
    keys.removeAll(initialKeys);
    if (!keys.isEmpty()) auth.redis.delete(keys);
  }

  long user(String suffix, String roles) {
    return db.queryForObject("""
        INSERT INTO users(employee_no,name,password_hash,roles,department_id,must_change_password)
        VALUES(?,?,?,?,?,false) RETURNING id
        """, Long.class, prefix + suffix, prefix + suffix, auth.encoder.encode(password), roles, dept);
  }

  String login(String suffix) {
    return http.postForObject("/api/auth/login",
        Map.of("login", prefix + suffix, "password", password), Map.class).get("token").toString();
  }

  ResponseEntity<String> get(String path, String token) {
    var h = new HttpHeaders(); h.setBearerAuth(token);
    return http.exchange("/api" + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
  }

  long expense(long applicant, String state, String purpose) {
    long id = db.queryForObject("""
        INSERT INTO requests(user_id,kind,status,amount,reason,created_at)
        VALUES(?,'EXPENSE',?,123.45,?,'2034-06-10') RETURNING id
        """, Long.class, applicant, state, prefix);
    db.update("INSERT INTO expense_items(request_id,line_no,description,amount) VALUES(?,1,?,123.45)", id, purpose);
    db.update("INSERT INTO approval_steps(request_id,step,approver_id,status) VALUES(?,1,?,'PENDING')", id, manager);
    return id;
  }

  void step(long id, long approver, Long actor) {
    db.update("""
        INSERT INTO approval_steps(request_id,step,approver_id,actor_id,status,acted_at)
        VALUES(?,1,?,?,'APPROVED','2034-06-11')
        ON CONFLICT(request_id,step) DO UPDATE SET approver_id=excluded.approver_id,
          actor_id=excluded.actor_id,status=excluded.status,acted_at=excluded.acted_at
        """, id, approver, actor);
  }

  String export(String token, String extra) {
    var r = get("/admin/exports/expenses-monthly.csv?month=2034-06&departmentId=" + dept + extra, token);
    assertEquals(200, r.getStatusCode().value(), r.toString());
    return r.getBody();
  }

  Set<Long> csvIds(String csv) {
    var result = new HashSet<Long>();
    var matcher = java.util.regex.Pattern.compile("(?m)^\"([0-9]+)\",").matcher(csv);
    while (matcher.find()) result.add(Long.parseLong(matcher.group(1)));
    return result;
  }

  @Test void financeVisibilityAgreesAcrossDetailsListsAndMonthlyExports() throws Exception {
    var hidden = new LinkedHashMap<String, Long>();
    for (String state : List.of("PENDING", "REJECTED", "WITHDRAWN", "CANCELLED"))
      {
      long id = expense(owner, state, prefix + state);
      hidden.put(state, id);
      db.update("UPDATE requests SET amount=? WHERE id=?", 90000 + id, id);
    }
    long approved = expense(owner, "APPROVED", prefix + "approved");
    step(approved, manager, manager);
    long own = expense(finance, "PENDING", prefix + "own");
    long assigned = expense(owner, "PENDING", prefix + "assigned"); step(assigned, finance, null);
    long acted = expense(owner, "PENDING", prefix + "acted"); step(acted, manager, finance);
    long cc = expense(owner, "PENDING", prefix + "cc");
    db.update("INSERT INTO request_cc(request_id,user_id) VALUES(?,?)", cc, finance);
    long reassigned = expense(owner, "PENDING", prefix + "reassigned");
    db.update("""
        INSERT INTO approval_reassignments(business,target_id,previous_approver_id,approver_id,actor_id,note)
        VALUES('REQUEST',?,?,?,?,?)
        """, reassigned, finance, manager, admin, prefix);
    long newApprover = expense(owner, "PENDING", prefix + "newApprover");
    db.update("""
        INSERT INTO approval_reassignments(business,target_id,previous_approver_id,approver_id,actor_id,note)
        VALUES('REQUEST',?,?,?,?,?)
        """, newApprover, manager, finance, admin, prefix);
    var visible = Set.of(approved, own, assigned, acted, cc, reassigned, newApprover);
    String csv = export(financeToken, "");
    assertEquals(visible, csvIds(csv));
    assertTrue(csv.contains("\"864.15\"")); // seven visible amounts only
    for (var e : hidden.entrySet()) {
      assertEquals(403, get("/requests/" + e.getValue(), financeToken).getStatusCode().value());
      assertFalse(csv.contains(prefix + e.getKey()));
      assertFalse(csvIds(csv).contains(e.getValue()));
      assertFalse(csv.contains(Long.toString(90000 + e.getValue()) + ".00"));
      assertFalse(export(financeToken, "&status=" + e.getKey()).contains(prefix + e.getKey()));
      assertFalse(csvIds(export(financeToken, "&status=" + e.getKey())).contains(e.getValue()));
      assertFalse(export(financeToken, "&status=" + e.getKey()).contains(Long.toString(90000 + e.getValue()) + ".00"));
      var adminStatus = new HashSet<Long>();
      adminStatus.add(e.getValue());
      if (e.getKey().equals("PENDING")) {
        adminStatus.addAll(visible);
        adminStatus.remove(approved);
      }
      assertEquals(adminStatus, csvIds(export(adminToken, "&status=" + e.getKey())));

    }
    var listed = new HashSet<Long>();
    for (int offset = 0; ; offset += 200) {
      var response = get("/requests?kind=EXPENSE&offset=" + offset, financeToken);
      assertEquals(200, response.getStatusCode().value());
      var list = json.readTree(response.getBody());
      for (var row : list) if (row.get("created_at").asText().startsWith("2034-06") &&
          row.get("reason").asText().equals(prefix)) listed.add(row.get("id").asLong());
      if (list.size() < 200) break;
    }
    assertEquals(visible, listed);
    for (long id : visible) assertEquals(200, get("/requests/" + id, financeToken).getStatusCode().value());
    assertEquals(Set.of(approved), csvIds(export(financeToken, "&basis=approved")));
    assertEquals(Set.of(approved), csvIds(export(financeToken, "&status=APPROVED")));
    var all = new HashSet<>(visible); all.addAll(hidden.values());
    assertEquals(all, csvIds(export(adminToken, "")));
    // No department filter: administrator also sees records in a different department.
    long otherDept = db.queryForObject("INSERT INTO departments(name,parent_id) VALUES(?,?) RETURNING id", Long.class, prefix + "other", dept);
    db.update("UPDATE users SET department_id=? WHERE id=?", otherDept, owner);
    assertEquals(all, csvIds(get("/admin/exports/expenses-monthly.csv?month=2034-06", adminToken).getBody())
        .stream().filter(all::contains).collect(java.util.stream.Collectors.toSet()));
    assertEquals(all, csvIds(export(adminToken, ""))); // descendant department filter preserved
    assertEquals(400, get("/admin/exports/expenses-monthly.csv?month=2034-06&departmentId=0", adminToken).getStatusCode().value());
    for (String token : List.of(employeeToken, managerToken))
      assertEquals(403, get("/admin/exports/expenses-monthly.csv?month=2034-06", token).getStatusCode().value());
  }

  @Test void monthlyPurposeFormulaEscapingIsIndependentOfVisibility() {
    expense(owner, "APPROVED", " \n=HYPERLINK(\"x\")");
    assertTrue(export(adminToken, "").contains("\"' \n=HYPERLINK(\"\"x\"\")\""));
  }

  @Test void contractExportEscapesLeadingSpaceAndNewlineFormulas() {
    var h = new HttpHeaders(); h.setBearerAuth(employeeToken);
    var response = http.exchange("/api/contracts", HttpMethod.POST, new HttpEntity<>(Map.of(
        "name", " =HYPERLINK(\"x\")", "type", "SERVICE", "counterparty", "\n+cmd",
        "amount", 1, "signedDate", "2034-06-01", "effectiveDate", "2034-06-01"), h), Map.class);
    assertEquals(200, response.getStatusCode().value(), response.toString());
    db.update("UPDATE contracts SET name=?,counterparty=? WHERE id=?",
        " =HYPERLINK(\"x\")", "\n+cmd", response.getBody().get("id"));
    String csv = get("/contracts/export", adminToken).getBody();
    assertTrue(csv.contains("\"' =HYPERLINK(\"\"x\"\")\""));
    assertTrue(csv.contains("\"'\n+cmd\""));
  }

  int attempt(String remote, String client, String login, String pw) throws Exception {
    return loginMvc.perform(post("/api/auth/login").with(r -> {r.setRemoteAddr(remote); return r;})
        .header("X-OA-Client-IP", client).header("X-Forwarded-For", client).header("CF-Connecting-IP", client)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("login", login, "password", pw))))
        .andReturn().getResponse().getStatus();
  }

  @Test void trustedClientsHaveSeparateFailureBucketsAndSuccessDoesNotConsumeQuota() throws Exception {
    for (int i = 0; i < 100; i++)
      assertEquals(401, attempt("127.0.0.1", "192.0.2.10", prefix + i, password));
    assertEquals(429, attempt("127.0.0.1", "192.0.2.10", prefix + "owner", password));
    for (int i = 0; i < 3; i++)
      assertEquals(200, attempt("127.0.0.1", "192.0.2.11", prefix + "owner", password));
    assertNull(auth.redis.opsForValue().get("login-ip:192.0.2.11"));
  }

  @Test void untrustedForwardingHeadersCannotRotateFailureBucket() throws Exception {
    for (int i = 0; i < 100; i++)
      assertEquals(401, attempt("203.0.113.9", "192.0.2." + (i + 1), prefix + i, password));
    assertEquals("100", auth.redis.opsForValue().get("login-ip:203.0.113.9"));
    assertEquals(429, attempt("203.0.113.9", "192.0.2.200", prefix + "owner", password));
  }

  @Test void accountAliasesAndMultipleIpsShareLockExpiryAndSuccessReset() throws Exception {
    String phone = "139" + String.format("%08d", owner);
    db.update("UPDATE users SET phone=? WHERE id=?", phone, owner);
    String key = "login-user:" + owner;
    for (int i = 0; i < 5; i++)
      assertEquals(401, attempt("127.0.0.1", "198.51.100." + (i + 1),
          i % 2 == 0 ? "  " + (prefix + "owner").toUpperCase(Locale.ROOT) + "  " : phone, "wrong"));
    assertEquals("5", auth.redis.opsForValue().get(key));
    assertTrue(auth.redis.getExpire(key) > 0 && auth.redis.getExpire(key) <= 900);
    assertEquals(429, attempt("127.0.0.1", "198.51.100.9", phone, password));
    assertEquals(429, attempt("127.0.0.1", "198.51.100.10", prefix + "owner", password));
    for (String k : List.of(key, "login:" + phone, "login:" + prefix + "owner"))
      auth.redis.expire(k, Duration.ofMillis(1));
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (auth.redis.hasKey(key) && System.nanoTime() < deadline) Thread.yield();
    assertEquals(200, attempt("127.0.0.1", "198.51.100.11", phone, password));
    assertEquals(401, attempt("127.0.0.1", "198.51.100.11", phone, "wrong"));
    assertEquals(200, attempt("127.0.0.1", "198.51.100.11", prefix + "owner", password));
    assertNull(auth.redis.opsForValue().get(key));
    assertNull(auth.redis.opsForValue().get("login:" + phone));
  }

  @Test void unknownNamesNormalizeAndMissingProxyHeaderUsesSeparateConservativeBucket() throws Exception {
    for (int i = 0; i < 5; i++)
      assertEquals(401, attempt("127.0.0.1", "invalid", " " + prefix.toUpperCase(Locale.ROOT) + " ", "wrong"));
    assertEquals(429, attempt("127.0.0.1", "invalid", prefix, password));
    assertEquals("5", auth.redis.opsForValue().get("login-ip:proxy-unknown"));
    assertEquals(200, attempt("127.0.0.1", "invalid", prefix + "owner", password));
    assertNull(auth.redis.opsForValue().get("login-ip:127.0.0.1"));
    assertEquals(200, loginMvc.perform(post("/api/auth/login")
        .with(r -> {r.setRemoteAddr("127.0.0.1"); return r;})
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("login", prefix + "owner", "password", password))))
        .andReturn().getResponse().getStatus());
    auth.redis.opsForValue().set("login-ip:proxy-unknown", "999", Duration.ofMinutes(15));
    assertEquals(401, attempt("127.0.0.1", "invalid", prefix + "another", "wrong"));
    assertEquals(429, attempt("127.0.0.1", "invalid", prefix + "owner", password));
    assertEquals(200, attempt("127.0.0.1", "192.0.2.20", prefix + "owner", password));
  }
}
