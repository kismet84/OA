package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** All concurrency, deadlock reproduction and migration work stays in oa_batch2_test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/oa_batch2_test",
    "spring.datasource.hikari.connection-init-sql=SET TIME ZONE 'Asia/Shanghai'; SET lock_timeout='5s'; SET statement_timeout='10s'",
    "spring.data.redis.database=11", "oa.fcm-credentials=", "oa.uploads=../.runtime/batch2-test-uploads"})
@TestMethodOrder(MethodOrderer.MethodName.class)
class RemediationBatch2HttpTest {
  @Autowired JdbcTemplate db;
  @Autowired Auth auth;
  @Autowired WorkflowService workflow;
  @Autowired ApprovalUrgeService urges;
  @Autowired NotificationService notifications;
  @Autowired PlatformTransactionManager transactions;
  @Autowired TestRestTemplate http;
  @Autowired Environment env;
  @Autowired Clock clock;
  @MockitoBean PushSender sender;
  String prefix, password;
  long dept, owner, peer, manager, previous, actual, cc, finance, unrelated, admin;
  Map<Long, String> tokens = new HashMap<>();
  Set<String> initialKeys;
  List<UUID> files = new ArrayList<>();

  @BeforeEach void setup() throws Exception {
    assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"));
    initialKeys = new HashSet<>(auth.redis.keys("*"));
    prefix = "b2" + UUID.randomUUID().toString().substring(0, 8);
    password = UUID.randomUUID().toString();
    dept = db.queryForObject("INSERT INTO departments(name) VALUES(?) RETURNING id", Long.class, prefix);
    String hash = auth.encoder.encode(password);
    manager = user("manager", "SUPERVISOR", hash, null);
    owner = user("owner", "EMPLOYEE", hash, manager);
    peer = user("peer", "EMPLOYEE", hash, manager);
    previous = user("previous", "SUPERVISOR", hash, null);
    actual = user("actual", "EMPLOYEE", hash, null);
    cc = user("cc", "EMPLOYEE", hash, null);
    finance = user("finance", "FINANCE", hash, null);
    unrelated = user("unrelated", "EMPLOYEE", hash, null);
    admin = user("admin", "ADMIN", hash, null);
    db.update("UPDATE workflow_config SET auto_urge_enabled=true,auto_urge_hours=1,auto_urge_repeat_hours=2,urge_cooldown_minutes=120 WHERE id=1");
    Files.createDirectories(Path.of(env.getProperty("oa.uploads")));
  }

  @AfterEach void cleanup() throws Exception {
    var keys = new HashSet<>(auth.redis.keys("*")); keys.removeAll(initialKeys);
    if (!keys.isEmpty()) auth.redis.delete(keys);
    for (UUID file : files) Files.deleteIfExists(Path.of(env.getProperty("oa.uploads")).resolve(file.toString()));
  }

  long user(String suffix, String roles, String hash, Long boss) {
    return db.queryForObject("INSERT INTO users(employee_no,name,password_hash,roles,department_id,manager_id,must_change_password) VALUES(?,?,?,?,?,?,false) RETURNING id",
        Long.class, prefix + suffix, prefix + suffix, hash, roles, dept, boss);
  }
  Actor actor(long id) { return auth.user(id); }
  String token(long id) {
    return tokens.computeIfAbsent(id, uid -> {
      String login = db.queryForObject("SELECT employee_no FROM users WHERE id=?", String.class, uid);
      return http.postForObject("/api/auth/login", Map.of("login", login, "password", password), Map.class).get("token").toString();
    });
  }
  ResponseEntity<Map> post(String path, Object body, long user, String key) {
    var headers = new HttpHeaders(); headers.setBearerAuth(token(user));
    if (key != null) headers.set("Idempotency-Key", key);
    return http.exchange("/api" + path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
  }
  int get(String path, long user) {
    var headers = new HttpHeaders(); headers.setBearerAuth(token(user));
    return http.exchange("/api" + path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class).getStatusCode().value();
  }
  UUID file(long user) throws Exception {
    UUID id = UUID.randomUUID(); files.add(id);
    db.update("INSERT INTO attachments(id,user_id,original_name,media_type) VALUES(?,?,'test.pdf','application/pdf')", id, user);
    try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
      pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      pdf.save(Path.of(env.getProperty("oa.uploads")).resolve(id.toString()).toFile());
    }
    return id;
  }
  WorkflowService.Application travel(int day, List<Long> recipients) {
    var date = LocalDate.of(LocalDate.now(clock).getYear() + 1, 1, 1).plusDays(day);
    return new WorkflowService.Application(null, "TRAVEL", null, date.atTime(9,0), date.atTime(18,0), null, prefix, null, null, null, recipients, null);
  }
  WorkflowService.Application expense(UUID file) {
    return new WorkflowService.Application(null, "EXPENSE", null, null, null, new BigDecimal("10"), prefix, null,
        List.of(new WorkflowService.ExpenseItem(prefix, new BigDecimal("10"))), List.of(file), null, null);
  }
  long id(ResponseEntity<Map> response) {
    assertEquals(200, response.getStatusCode().value(), response.toString());
    return ((Number) response.getBody().get("id")).longValue();
  }
  <T> T tx(Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
  void barrier(CyclicBarrier barrier) {
    try { barrier.await(5, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
  }
  List<Object> parallel(Supplier<?> a, Supplier<?> b) throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      var tasks = List.of(a, b).stream().map(work -> executor.submit(() -> {
        assertTrue(gate.await(5, TimeUnit.SECONDS));
        try { return (Object) work.get(); } catch (RuntimeException failure) { return (Object) failure; }
      })).toList();
      gate.countDown();
      return List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS));
    }
  }
  WorkflowService synchronizedWorkflow(boolean legacy, CyclicBarrier gate) {
    var service = new WorkflowService(db, auth, clock) {
      @Override Map<String, Object> lockedUser(long user) {
        var row = legacy ? db.queryForList("SELECT * FROM users WHERE id=? FOR UPDATE", user).getFirst() : super.lockedUser(user);
        barrier(gate);
        return row;
      }
    };
    service.notifications = notifications; service.uploads = env.getProperty("oa.uploads");
    return service;
  }
  boolean sqlState(Object value, String state) {
    if (!(value instanceof Throwable error)) return false;
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
    return false;
  }

  @Test void aLegacyUserLockReproducesDeadlockInIsolatedDatabase() throws Exception {
    var service = synchronizedWorkflow(true, new CyclicBarrier(2));
    var results = parallel(() -> tx(() -> service.submit(travel(0, List.of(peer)), actor(owner))),
        () -> tx(() -> service.submit(travel(0, List.of(owner)), actor(peer))));
    assertEquals(1, results.stream().filter(v -> sqlState(v, "40P01")).count(), results.toString());
    assertEquals(1, results.stream().filter(Long.class::isInstance).count());
  }

  @Test void bMutualCcSubmissionsAndApprovalNotificationsSucceedForThirtyRounds() throws Exception {
    var service = synchronizedWorkflow(false, new CyclicBarrier(2));
    for (int round = 0; round < 30; round++) {
      int day = round;
      var results = parallel(() -> tx(() -> service.submit(travel(day, List.of(peer)), actor(owner))),
          () -> tx(() -> service.submit(travel(day, List.of(owner)), actor(peer))));
      assertTrue(results.stream().allMatch(Long.class::isInstance), results.toString());
      long a = (long) results.get(0), b = (long) results.get(1);
      var approvals = parallel(() -> { workflow.decide(a, new WorkflowService.Decision(true, null), actor(manager)); return true; },
          () -> { workflow.decide(b, new WorkflowService.Decision(true, null), actor(manager)); return true; });
      assertEquals(List.of(true, true), approvals);
    }
    assertEquals(60, db.queryForObject("SELECT count(*) FROM requests WHERE reason=? AND status='APPROVED'", Integer.class, prefix));
  }

  WorkflowService.Application leave(int day, long type) {
    var in = travel(day, null);
    return new WorkflowService.Application(null, "LEAVE", type, in.startAt(), in.endAt(), null, prefix, null, null, null, null, null);
  }
  @Test void cSameOwnerBalanceAndAnnualLimitRemainSerialized() throws Exception {
    long annual = db.queryForObject("SELECT id FROM leave_types WHERE code='ANNUAL'", Long.class);
    int year = travel(0, null).startAt().getYear();
    db.update("INSERT INTO balances VALUES(?,?,?,1)", owner, annual, year);
    var created = parallel(() -> workflow.submit(leave(0, annual), actor(owner)), () -> workflow.submit(leave(1, annual), actor(owner)));
    assertTrue(created.stream().allMatch(Long.class::isInstance), created.toString());
    var approvals = parallel(() -> { workflow.decide((long) created.get(0), new WorkflowService.Decision(true, null), actor(manager)); return true; },
        () -> { workflow.decide((long) created.get(1), new WorkflowService.Decision(true, null), actor(manager)); return true; });
    assertEquals(1, approvals.stream().filter(Boolean.TRUE::equals).count());
    assertEquals(1, approvals.stream().filter(ApiException.class::isInstance).count());
    assertEquals(0, db.queryForObject("SELECT amount FROM balances WHERE user_id=? AND type_id=? AND year=?", BigDecimal.class, owner, annual, year).signum());
    long limited = db.queryForObject("INSERT INTO leave_types(code,name,annual_limit) VALUES(?,?,1) RETURNING id", Long.class, prefix, prefix);
    long a = workflow.submit(leave(2, limited), actor(owner)), b = workflow.submit(leave(3, limited), actor(owner));
    var outcomes = parallel(() -> { workflow.decide(a, new WorkflowService.Decision(true,null), actor(manager)); return true; },
        () -> { workflow.decide(b, new WorkflowService.Decision(true,null), actor(manager)); return true; });
    assertEquals(1, outcomes.stream().filter(Boolean.TRUE::equals).count());
    assertEquals(1, outcomes.stream().filter(ApiException.class::isInstance).count());
  }
  @Test void dConcurrentCorrectionAndOvertimeSubmissionsPreventDuplicates() throws Exception {
    var at = LocalDateTime.now(clock).minusDays(1).withNano(0);
    var correction = new WorkflowService.Application(null,"CORRECTION",null,at,null,null,prefix,null,null,null,null,"NORMAL");
    var results = parallel(() -> workflow.submit(correction,actor(owner)), () -> workflow.submit(correction,actor(owner)));
    assertEquals(1, results.stream().filter(Long.class::isInstance).count());
    assertEquals(1, results.stream().filter(ApiException.class::isInstance).count());
    var t = travel(0,null);
    var overtime = new WorkflowService.Application(null,"OVERTIME",null,t.startAt(),t.endAt(),null,prefix,null,null,null,null,null);
    results = parallel(() -> workflow.submit(overtime,actor(owner)), () -> workflow.submit(overtime,actor(owner)));
    assertEquals(1, results.stream().filter(Long.class::isInstance).count());
    assertEquals(1, results.stream().filter(ApiException.class::isInstance).count());
  }

  @Test void eIdempotencyReplaysAndConcurrentReplaysReturnOneCompleteExpense() throws Exception {
    var input = expense(file(owner)); String key = UUID.randomUUID().toString();
    // Establish session before concurrent HTTP calls (tokens map itself is not shared-mutated).
    token(owner);
    var results = parallel(() -> post("/requests",input,owner,key), () -> post("/requests",input,owner,key));
    long first = id((ResponseEntity<Map>) results.get(0));
    assertEquals(first, id((ResponseEntity<Map>) results.get(1)));
    assertEquals(first, id(post("/requests",input,owner,key)));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM requests WHERE user_id=? AND client_request_id=?", Integer.class, owner,key));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM expense_items WHERE request_id=?", Integer.class,first));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM expense_attachments WHERE request_id=?", Integer.class,first));
    assertEquals(1, db.queryForObject("SELECT count(*) FROM approval_steps WHERE request_id=?", Integer.class,first));
  }
  @Test void fKeysAndUsersAreIndependentAndMissingKeysRemainCompatible() throws Exception {
    String key = UUID.randomUUID().toString();
    long a = id(post("/requests",expense(file(owner)),owner,key));
    long b = id(post("/requests",expense(file(peer)),peer,key));
    assertNotEquals(a,b);
    assertEquals(403,get("/requests/"+a,peer));
    assertEquals(403,get("/requests/"+b,owner));
    long c = id(post("/requests",expense(file(owner)),owner,UUID.randomUUID().toString()));
    long d = id(post("/requests",expense(file(owner)),owner,null));
    long e = id(post("/requests",expense(file(owner)),owner,null));
    assertEquals(5,Set.of(a,b,c,d,e).size());
    assertEquals(400,post("/requests",expense(file(owner)),owner,"invalid key!").getStatusCode().value());
  }

  @Test void gDetailsAndAttachmentsAgreeForReassignmentActorCcAndFinance() throws Exception {
    for (String kind : List.of("EXPENSE", "LEAVE")) {
      UUID file = file(owner);
      WorkflowService.Application input;
      if (kind.equals("EXPENSE")) input=expense(file);
      else { var t=travel(0,null); input=new WorkflowService.Application(null,"LEAVE",
          db.queryForObject("SELECT id FROM leave_types WHERE code='SICK'",Long.class),t.startAt(),t.endAt(),null,prefix,file,null,null,null,null); }
      long id=workflow.submit(input,actor(owner));
      workflow.reassign(id,new ApprovalSupport.Reassign(previous,"test reassignment"),actor(admin));
      workflow.reassign(id,new ApprovalSupport.Reassign(manager,"test reassignment back"),actor(admin));
      db.update("UPDATE approval_steps SET actor_id=? WHERE request_id=?",actual,id);
      db.update("INSERT INTO request_cc VALUES(?,?)",id,cc);
      for (long user : List.of(owner,previous,manager,actual,cc,admin)) {
        assertEquals(200,get("/requests/"+id,user));
        assertEquals(200,get("/attachments/"+file,user));
      }
      for (long user : List.of(unrelated,finance)) {
        assertEquals(403,get("/requests/"+id,user));
        assertEquals(403,get("/attachments/"+file,user));
      }
      workflow.decide(id,new WorkflowService.Decision(true,null),actor(manager));
      assertEquals(kind.equals("EXPENSE")?200:403,get("/requests/"+id,finance));
      assertEquals(kind.equals("EXPENSE")?200:403,get("/attachments/"+file,finance));
    }
  }
  long contract(UUID file) {
    long id=db.queryForObject("INSERT INTO contracts(code,name,type,counterparty,user_id,department_id,signed_date,effective_date) VALUES(?,?,'OTHER','test',?,?,current_date,current_date) RETURNING id",Long.class,UUID.randomUUID().toString(),prefix,owner,dept);
    if(file!=null)db.update("INSERT INTO contract_files(contract_id,attachment_id) VALUES(?,?)",id,file);
    return id;
  }
  @Test void hOtherBusinessFilesCannotBeClaimedByRequestsAndReverseDirectionIsRejected() throws Exception {
    UUID contractFile=file(owner); contract(contractFile);
    UUID documentFile=file(owner); db.update("INSERT INTO document_file_history VALUES(?)",documentFile);
    UUID announcementFile=file(owner);
    long announcement=db.queryForObject("INSERT INTO announcements(title,body,publisher_id) VALUES(?,'test',?) RETURNING id",Long.class,prefix,admin);
    db.update("INSERT INTO announcement_attachments VALUES(?,?)",announcement,announcementFile);
    UUID assetFile=file(owner);
    long category=db.queryForObject("INSERT INTO asset_categories(name,kind) VALUES(?,'ITEM') RETURNING id",Long.class,prefix);
    db.update("INSERT INTO assets(category_id,code,name,attachment_id) VALUES(?,?,?,?)",category,prefix,prefix,assetFile);
    for(UUID occupied:List.of(contractFile,documentFile,announcementFile,assetFile)) {
      var r=post("/requests",expense(occupied),owner,null);
      assertEquals(400,r.getStatusCode().value(),r.toString());
      assertEquals("该附件已用于其他业务，请重新上传",r.getBody().get("message"));
    }
    var t=travel(0,null);
    var proof=new WorkflowService.Application(null,"LEAVE",db.queryForObject("SELECT id FROM leave_types WHERE code='SICK'",Long.class),t.startAt(),t.endAt(),null,prefix,documentFile,null,null,null,null);
    assertEquals(400,post("/requests",proof,owner,null).getStatusCode().value());
    assertEquals(0,db.queryForObject("SELECT count(*) FROM requests WHERE user_id=?",Integer.class,owner));
    UUID fresh=file(owner); long request=id(post("/requests",expense(fresh),owner,null));
    assertEquals(200,get("/attachments/"+fresh,owner));assertEquals(200,get("/attachments/"+fresh,manager));
    assertEquals(400,post("/requests",expense(fresh),owner,null).getStatusCode().value());
    var body=Map.of("name",prefix,"type","OTHER","counterparty","test","signedDate","2030-01-01","effectiveDate","2030-01-01","attachmentIds",List.of(fresh));
    assertEquals(400,post("/contracts",body,owner,null).getStatusCode().value());
    assertEquals(200,get("/requests/"+request,owner));
    for(String business:List.of("CONTRACT","DOCUMENT","ANNOUNCEMENT","ASSET"))
      assertThrows(ApiException.class,()->tx(()->{AttachmentClaims.check(db,fresh,owner,business,null);return true;}));
  }
  @Test void hReverseHttpAssociationsAllRejectExpenseAttachment() throws Exception {
    db.update("UPDATE users SET manager_id=? WHERE id=?", manager, admin);
    UUID used = file(admin);
    id(post("/requests", expense(used), admin, null));
    long category=db.queryForObject("INSERT INTO asset_categories(name,kind) VALUES(?,'ITEM') RETURNING id",Long.class,prefix);
    var inputs = Map.of(
        "/contracts", Map.of("name",prefix,"type","OTHER","counterparty","test","signedDate","2030-01-01","effectiveDate","2030-01-01","attachmentIds",List.of(used)),
        "/documents", Map.of("title",prefix,"category","POLICY","attachmentIds",List.of(used)),
        "/announcements", Map.of("title",prefix,"body","test","attachmentIds",List.of(used)),
        "/assets", Map.of("categoryId",category,"code",prefix,"name",prefix,"enabled",true,"attachmentId",used));
    for (var entry : inputs.entrySet()) {
      var response=post(entry.getKey(),entry.getValue(),admin,null);
      assertEquals(400,response.getStatusCode().value(),entry.getKey()+response);
      assertEquals("该附件已用于其他业务，请重新上传",response.getBody().get("message"));
    }
  }

  @Test void iConcurrentCrossBusinessClaimsSerializeOnAttachment() throws Exception {
    UUID fresh=file(owner);
    var results=parallel(()->tx(()->{AttachmentClaims.check(db,fresh,owner,"CONTRACT",null);return contract(fresh);}),
        ()->workflow.submit(expense(fresh),actor(owner)));
    assertEquals(1,results.stream().filter(Long.class::isInstance).count(),results.toString());
    assertEquals(1,results.stream().filter(ApiException.class::isInstance).count(),results.toString());
  }

  void age(long id) { db.update("UPDATE approval_steps SET activated_at=now()-interval '3 hours' WHERE request_id=?",id); }
  @Test void jAutoSkipsLockedContractAndRequestAndReleasesEachRowBeforeNextCandidate() throws Exception {
    long blockedContract=contract(null);
    db.update("UPDATE contracts SET status='PENDING',revision=1 WHERE id=?",blockedContract);
    db.update("INSERT INTO contract_steps(contract_id,revision,step,approver_id,activated_at) VALUES(?,1,1,?,now()-interval '3 hours')",blockedContract,manager);
    long blockedRequest=workflow.submit(travel(0,null),actor(owner));age(blockedRequest);
    long healthy=workflow.submit(travel(1,null),actor(owner));age(healthy);
    try(var connection=db.getDataSource().getConnection()) {
      connection.setAutoCommit(false);
      try(var statement=connection.createStatement()) {
        statement.executeQuery("SELECT id FROM contracts WHERE id="+blockedContract+" FOR UPDATE").close();
        statement.executeQuery("SELECT id FROM requests WHERE id="+blockedRequest+" FOR UPDATE").close();
      }
      assertTimeoutPreemptively(Duration.ofSeconds(5),()->urges.auto());
      assertEquals(0,db.queryForObject("SELECT count(*) FROM approval_urges WHERE (business='REQUEST' AND target_id=?) OR (business='CONTRACT' AND target_id=?)",Integer.class,blockedRequest,blockedContract));
      assertEquals(1,db.queryForObject("SELECT count(*) FROM approval_urges WHERE business='REQUEST' AND target_id=?",Integer.class,healthy));
      assertTimeoutPreemptively(Duration.ofSeconds(3),()->workflow.decide(healthy,new WorkflowService.Decision(true,null),actor(manager)));
      long withdraw=workflow.submit(travel(2,null),actor(owner));
      assertTimeoutPreemptively(Duration.ofSeconds(3),()->workflow.cancel(withdraw,actor(owner)));
      connection.rollback();
    }
    assertEquals(1,urges.autoForDev(clock.instant(),blockedRequest));
  }
  @Test void kAutoAndManualCooldownSerializeAndAllowNextWindow() throws Exception {
    long id=workflow.submit(travel(0,null),actor(owner));age(id);
    var outcomes=parallel(()->urges.autoForDev(clock.instant(),id),()->urges.manual("REQUEST",id,actor(owner)));
    assertEquals(1,db.queryForObject("SELECT count(*) FROM approval_urges WHERE business='REQUEST' AND target_id=?",Integer.class,id));
    assertTrue(outcomes.stream().noneMatch(v->v instanceof Exception && !(v instanceof ApiException)),outcomes.toString());
    assertEquals(0,urges.autoForDev(clock.instant(),id));
    assertThrows(ApiException.class,()->urges.manual("REQUEST",id,actor(owner)));
    db.update("UPDATE approval_urges SET created_at=now()-interval '121 minutes' WHERE business='REQUEST' AND target_id=?",id);
    assertEquals(1,urges.autoForDev(clock.instant(),id));
    assertEquals(0,urges.autoForDev(clock.instant(),id));
    assertEquals(2,db.queryForObject("SELECT count(*) FROM approval_urges WHERE business='REQUEST' AND target_id=?",Integer.class,id));
  }
  @Test void lPushFailureOccursAfterCommitForManualAndAutoWithoutRollingBackRecords() throws Exception {
    long a=workflow.submit(travel(0,null),actor(owner)),b=workflow.submit(travel(1,null),actor(owner));age(b);
    db.update("INSERT INTO push_tokens(user_id,token,platform) VALUES(?,?,'ANDROID')",manager,UUID.randomUUID().toString());
    var delivered=new CountDownLatch(2);var committed=new AtomicBoolean(true);
    doAnswer(invocation->{
      var notice=invocation.getArgument(1,NotificationService.Created.class);
      if (notice.userId()==manager && notice.message().startsWith("【")) {
        long id=Long.parseLong(notice.data().get("request_id"));
        try(var connection=db.getDataSource().getConnection();var stmt=connection.createStatement()) {
          connection.setAutoCommit(false);
          stmt.executeQuery("SELECT id FROM requests WHERE id="+id+" FOR UPDATE NOWAIT").close();
          committed.compareAndSet(true,db.queryForObject("SELECT count(*) FROM approval_urges WHERE business='REQUEST' AND target_id=?",Integer.class,id)>0);
          connection.rollback();
        } catch(Exception e){committed.set(false);}
        delivered.countDown();
      }
      throw new IllegalStateException("simulated transport failure");
    }).when(sender).send(anyString(),any());
    urges.manual("REQUEST",a,actor(owner));
    assertEquals(1,urges.autoForDev(clock.instant(),b));
    assertTrue(delivered.await(5,TimeUnit.SECONDS));assertTrue(committed.get());
    assertEquals(2,db.queryForObject("SELECT count(*) FROM approval_urges WHERE business='REQUEST' AND target_id IN (?,?)",Integer.class,a,b));
  }

  @Test void mMigrationPreservesExistingV16RowsAndEnforcesPartialUniqueIndex() throws Exception {
    String schema="migration_"+UUID.randomUUID().toString().replace("-","");
    var config=Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema).target("16");
    config.load().migrate();
    try(var connection=db.getDataSource().getConnection();var statement=connection.createStatement()) {
      statement.execute("SET search_path TO "+schema);
      statement.execute("INSERT INTO departments(name) VALUES('fixture')");
      try(var insert=connection.prepareStatement("INSERT INTO users(employee_no,name,password_hash,roles,department_id) VALUES('fixture','fixture',?,'EMPLOYEE',1)")) {
        insert.setString(1,UUID.randomUUID().toString());insert.executeUpdate();
      }
      statement.execute("INSERT INTO requests(user_id,kind,reason) VALUES(1,'EXPENSE','existing')");
      Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema).load().migrate();
      try(var result=statement.executeQuery("SELECT client_request_id FROM requests")){assertTrue(result.next());assertNull(result.getString(1));}
      statement.execute("INSERT INTO requests(user_id,kind,reason) VALUES(1,'EXPENSE','legacy'),(1,'EXPENSE','legacy')");
      statement.execute("INSERT INTO requests(user_id,kind,reason,client_request_id) VALUES(1,'EXPENSE','keyed','test-key')");
      assertEquals("23505",assertThrows(SQLException.class,()->statement.execute("INSERT INTO requests(user_id,kind,reason,client_request_id) VALUES(1,'EXPENSE','duplicate','test-key')")).getSQLState());
    } finally { db.execute("DROP SCHEMA "+schema+" CASCADE"); }
  }
}
