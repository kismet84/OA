package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.*;

/** Real HTTP + PostgreSQL + Redis, mock only the outbound transport. Separate development DB. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
  "spring.datasource.url=${PUSH_TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55432/oa_push_test}",
  "oa.fcm-credentials=", "spring.data.redis.database=14"
})
@Import(PushHttpTest.Endpoints.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushHttpTest {
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate db;
  @Autowired Auth auth;
  @Autowired NotificationService notifications;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean PushSender sender;
  String a,b;
  long user,other;

  @RestController
  static class Endpoints {
    @Autowired WorkflowService workflow;
    @Autowired NotificationService notices;
    @PostMapping("/api/test-push")
    @org.springframework.transaction.annotation.Transactional
    Object write(jakarta.servlet.http.HttpServletRequest req) {
      notices.write(Auth.actor(req).id(), "测试系统通知");
      return Map.of("message","已写入");
    }
  }
  @BeforeAll void setup() {
    String prefix="push"+UUID.randomUUID().toString().substring(0,8);
    long department=db.queryForObject("INSERT INTO departments(name) VALUES(?) RETURNING id",Long.class,prefix);
    String hash=auth.encoder.encode("Push@Test2026");
    user=create(prefix+"a",department,hash); other=create(prefix+"b",department,hash);
    a=login(prefix+"a"); b=login(prefix+"b");
  }
  long create(String name,long dept,String hash) {
    return db.queryForObject("INSERT INTO users(employee_no,name,department_id,password_hash,roles,must_change_password) VALUES(?,?,?,?,'EMPLOYEE',false) RETURNING id",Long.class,name,name,dept,hash);
  }
  String login(String name) {
    var response=http.postForEntity("/api/auth/login",Map.of("login",name,"password","Push@Test2026"),Map.class);
    assertEquals(200,response.getStatusCode().value()); return (String)response.getBody().get("token");
  }
  ResponseEntity<String> call(String method,String path,String token,Object body) {
    var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
    if(token!=null) headers.setBearerAuth(token);
    return http.exchange("/api"+path,HttpMethod.valueOf(method),new HttpEntity<>(body,headers),String.class);
  }
  void register(String session,String value) { assertEquals(200,call("POST","/push/tokens",session,Map.of("token",value,"platform","ANDROID")).getStatusCode().value()); }
  void await(java.util.function.BooleanSupplier condition) throws Exception {
    long until=System.nanoTime()+Duration.ofSeconds(8).toNanos();
    while(!condition.getAsBoolean() && System.nanoTime()<until) Thread.sleep(20);
    assertTrue(condition.getAsBoolean());
  }
  @BeforeEach void clear() throws Exception {
    db.update("DELETE FROM push_tokens WHERE user_id IN (?,?)",user,other); reset(sender);
    when(sender.send(anyString(),any())).thenReturn(PushSender.Result.SENT);
  }
  @Test void authenticationValidationRebindAndUnregister() {
    assertEquals(401,call("POST","/push/tokens",null,Map.of("token","test","platform","ANDROID")).getStatusCode().value());
    assertEquals(401,call("DELETE","/push/tokens",null,Map.of("token","test")).getStatusCode().value());
    for(var body:List.of(Map.of("token"," ","platform","ANDROID"),Map.of("token","test","platform","IOS"),Map.of("token","test","platform","BAD")))
      assertEquals(400,call("POST","/push/tokens",a,body).getStatusCode().value());
    register(a,"shared-test-token"); register(a,"second-device"); register(b,"shared-test-token");
    assertEquals(other,db.queryForObject("SELECT user_id FROM push_tokens WHERE token='shared-test-token'",Long.class));
    assertEquals(Auth.sessionId(b),db.queryForObject("SELECT session_id FROM push_tokens WHERE token='shared-test-token'",String.class));
    assertEquals(200,call("DELETE","/push/tokens",a,Map.of("token","shared-test-token")).getStatusCode().value());
    assertEquals(1,db.queryForObject("SELECT count(*) FROM push_tokens WHERE user_id=?",Integer.class,other));
    call("DELETE","/push/tokens",b,Map.of("token","shared-test-token"));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM push_tokens WHERE user_id=?",Integer.class,other));
    assertEquals(0,db.queryForObject("SELECT count(*) FROM audit_logs WHERE target LIKE '%shared-test-token%'",Integer.class));
  }
  @Test void committedNotificationSendsToAllDevicesAndRemainsInBothMessageApisOnFailure() throws Exception {
    register(a,"first"); register(a,"second");
    when(sender.send(eq("first"),any())).thenThrow(new RuntimeException("mock transport failure"));
    assertEquals(200,call("POST","/test-push",a,Map.of()).getStatusCode().value());
    verify(sender,timeout(8000).times(2)).send(anyString(),argThat(n->n.message().equals("测试系统通知") && n.data().containsKey("notification_id")));
    long id=db.queryForObject("SELECT max(id) FROM notifications WHERE user_id=?",Long.class,user);
    for(String path:List.of("/messages?category=notifications","/notifications")) {
      var result=call("GET",path,a,null); assertEquals(200,result.getStatusCode().value());
      assertTrue(result.getBody().contains("测试系统通知")); assertTrue(result.getBody().contains("\"id\":"+id));
    }
  }
  @Test void invalidTokenIsRemovedButTransientFailureIsRetained() throws Exception {
    register(a,"invalid"); register(a,"transient");
    when(sender.send(eq("invalid"),any())).thenReturn(PushSender.Result.INVALID);
    when(sender.send(eq("transient"),any())).thenThrow(new RuntimeException());
    call("POST","/test-push",a,Map.of());
    await(()->db.queryForObject("SELECT count(*) FROM push_tokens WHERE token='invalid'",Integer.class)==0);
    assertEquals(1,db.queryForObject("SELECT count(*) FROM push_tokens WHERE token='transient'",Integer.class));
  }
  @Test void transactionRollbackDoesNotSendAndLogoutRemovesSessionDevices() throws Exception {
    register(b,"logout-device");
    new TransactionTemplate(transactions).execute(s->{ notifications.write(other,"回滚通知"); s.setRollbackOnly(); return null; });
    assertEquals(0,db.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND message='回滚通知'",Integer.class,other));
    verifyNoInteractions(sender);
    assertEquals(200,call("POST","/auth/logout",b,Map.of()).getStatusCode().value());
    assertEquals(0,db.queryForObject("SELECT count(*) FROM push_tokens WHERE token='logout-device'",Integer.class));
    b=login(db.queryForObject("SELECT employee_no FROM users WHERE id=?",String.class,other));
  }
  @Test void realTaskHttpPreservesTextAndAssociationInPayload() throws Exception {
    register(a,"task-device");
    var result=call("POST","/tasks",a,Map.of("title","推送任务验收","assigneeIds",List.of(user)));
    assertEquals(200,result.getStatusCode().value());
    var row=db.queryForMap("SELECT * FROM notifications WHERE user_id=? AND message='任务指派：推送任务验收' ORDER BY id DESC LIMIT 1",user);
    assertNotNull(row.get("task_id"));
    verify(sender,timeout(8000)).send(eq("task-device"),argThat(n->n.message().equals("任务指派：推送任务验收")
        && n.data().get("task_id").equals(row.get("task_id").toString()) && n.data().get("notification_id").equals(row.get("id").toString())));
  }
  @Test void visitorConflictSkippedRowDoesNotSendTwice() throws Exception {
    register(a,"visitor-device");
    long id=db.queryForObject("INSERT INTO visitors(host_id,name,phone,company,purpose,people,start_at,end_at,code,status)"
        + " VALUES(?,'来访测试','13800000000','测试单位','测试',1,localtimestamp,localtimestamp+interval '1 hour','123456','WAITING') RETURNING id",Long.class,user);
    var tx=new TransactionTemplate(transactions);
    tx.execute(s->{ notifications.write(user,"访客「来访测试」已到访，请接待（来访 #"+id+"）","visitor_id",id); return null; });
    verify(sender,timeout(8000)).send(eq("visitor-device"),argThat(n->n.data().get("visitor_id").equals(Long.toString(id))));
    tx.execute(s->{ notifications.write(user,"访客「来访测试」已到访，请接待（来访 #"+id+"）","visitor_id",id); return null; });
    assertEquals(1,db.queryForObject("SELECT count(*) FROM notifications WHERE visitor_id=?",Integer.class,id));
    // Submission is synchronous at commit; skipped rows never publish a second event.
    verify(sender,times(1)).send(eq("visitor-device"),any());
  }
  @Test void missingCredentialsSkipsWithoutReadingFiles() throws Exception {
    assertEquals(PushSender.Result.SKIPPED,new FcmPushSender("").send("test",new NotificationService.Created(user,"通知",Map.of("notification_id","1"))));
  }
}
