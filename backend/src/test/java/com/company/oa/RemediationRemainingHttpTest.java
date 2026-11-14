package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real database/transaction tests use only the existing isolated test database. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/oa_batch2_test",
      "spring.data.redis.database=11",
      "oa.fcm-credentials=",
      "oa.uploads=../.runtime/batch2-test-uploads"
    })
class RemediationRemainingHttpTest {
  @Autowired JdbcTemplate db;
  @Autowired Auth auth;
  @Autowired Clock clock;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean PushSender sender;
  String prefix;
  long dept, owner, peer, manager, actual, cc, unrelated, admin;

  @BeforeEach
  void setup() {
    assertTrue(db.queryForObject("SELECT current_database()", String.class).endsWith("_test"));
    prefix = "remain" + UUID.randomUUID().toString().substring(0, 8);
    dept =
        db.queryForObject(
            "INSERT INTO departments(name) VALUES(?) RETURNING id", Long.class, prefix);
    String hash = auth.encoder.encode(UUID.randomUUID().toString());
    manager = user("manager", "SUPERVISOR", hash, null);
    owner = user("owner", "EMPLOYEE", hash, manager);
    peer = user("peer", "EMPLOYEE", hash, manager);
    actual = user("actual", "EMPLOYEE", hash, null);
    cc = user("cc", "EMPLOYEE", hash, null);
    unrelated = user("unrelated", "EMPLOYEE", hash, null);
    admin = user("admin", "ADMIN", hash, null);
  }

  long user(String suffix, String roles, String hash, Long boss) {
    return db.queryForObject(
        "INSERT INTO"
            + " users(employee_no,name,password_hash,roles,department_id,manager_id,must_change_password)"
            + " VALUES(?,?,?,?,?,?,false) RETURNING id",
        Long.class,
        prefix + suffix,
        prefix + suffix,
        hash,
        roles,
        dept,
        boss);
  }

  Actor actor(long id) {
    return auth.user(id);
  }

  <T> T tx(Supplier<T> work) {
    return new TransactionTemplate(transactions).execute(s -> work.get());
  }

  WorkflowService.Application travel(int day, List<Long> recipients) {
    var date = LocalDate.of(LocalDate.now(clock).getYear() + 1, 1, 1).plusDays(day);
    return new WorkflowService.Application(
        null,
        "TRAVEL",
        null,
        date.atTime(9, 0),
        date.atTime(18, 0),
        null,
        prefix,
        null,
        null,
        null,
        recipients,
        null);
  }

  @Autowired TaskController tasks;

  @Test
  void approvalNotificationsLinkPendingApprovedRejectedAndWithdrawnRecipients() {
    var events = new ArrayList<NotificationService.Created>();
    var service =
        new NotificationService(db, event -> events.add((NotificationService.Created) event));
    var workflow = new WorkflowService(db, auth, clock);
    workflow.notifications = service;

    for (String outcome : List.of("APPROVED", "REJECTED", "WITHDRAWN")) {
      long id = tx(() -> workflow.submit(travel(events.size(), List.of(cc)), actor(owner)));
      assertNotice(id, manager, "待您处理");
      assertNotice(id, cc, "抄送给您");
      if (outcome.equals("WITHDRAWN")) {
        db.update(
            "INSERT INTO approval_steps(request_id,step,approver_id) VALUES(?,2,?)", id, actual);
        tx(
            () -> {
              workflow.decide(id, new WorkflowService.Decision(true, "测试"), actor(manager));
              return null;
            });
        assertNotice(id, actual, "待您处理");
        tx(
            () -> {
              workflow.cancel(id, actor(owner));
              return null;
            });
      } else
        tx(
            () -> {
              workflow.decide(
                  id,
                  new WorkflowService.Decision(outcome.equals("APPROVED"), "测试"),
                  actor(manager));
              return null;
            });
      assertNotice(
          id,
          outcome.equals("WITHDRAWN") ? manager : owner,
          outcome.equals("APPROVED") ? "已批准" : outcome.equals("REJECTED") ? "已驳回" : "撤销/撤回");
    }
    assertFalse(events.isEmpty());
    for (var event : events) {
      assertNotNull(event.data().get("request_id"));
      assertEquals(
          1L,
          db.queryForObject(
              "SELECT count(*) FROM notifications WHERE id=? AND user_id=? AND request_id=?",
              Long.class,
              Long.parseLong(event.data().get("notification_id")),
              event.userId(),
              Long.parseLong(event.data().get("request_id"))));
    }
  }

  void assertNotice(long id, long recipient, String text) {
    assertEquals(
        1L,
        db.queryForObject(
            "SELECT count(*) FROM notifications WHERE request_id=? AND user_id=? AND message LIKE"
                + " ?",
            Long.class,
            id,
            recipient,
            "%" + text + "%"));
  }

  long completedTask() {
    long id =
        ((Number)
                ((Map<?, ?>)
                        tx(
                            () ->
                                tasks.save(
                                    null,
                                    new TaskController.Input(
                                        "原标题", "原说明", "2035-01-01", List.of(peer)),
                                    actor(owner))))
                    .get("id"))
            .longValue();
    db.update("UPDATE tasks SET status='COMPLETED' WHERE id=?", id);
    db.update("UPDATE users SET enabled=false WHERE id=?", peer);
    return id;
  }

  @Test
  void completedTaskAllowsTitleAndDescriptionWithDisabledOriginalAssignee() {
    long id = completedTask();
    for (long editor : List.of(owner, admin)) {
      tx(
          () ->
              tasks.save(
                  id,
                  new TaskController.Input("新标题", "新说明", "2035-01-01", List.of(peer)),
                  actor(editor)));
      assertEquals(Set.of(peer), tasks.assignees(id));
      assertEquals(
          "新标题", db.queryForObject("SELECT title FROM tasks WHERE id=?", String.class, id));
      assertEquals(
          "新说明", db.queryForObject("SELECT description FROM tasks WHERE id=?", String.class, id));
      assertEquals(TaskController.due("2035-01-01"), tasks.task(id, actor(owner)).get("due_at"));
    }
  }

  @Test
  void completedTaskStillRequiresReopenForAssigneeOrDeadlineAndEnforcesPermissions() {
    long id = completedTask();
    for (var input :
        List.of(
            new TaskController.Input("标题", "说明", "2035-01-01", List.of(actual)),
            new TaskController.Input("标题", "说明", "2035-01-02", List.of(peer)))) {
      var error =
          assertThrows(ApiException.class, () -> tx(() -> tasks.save(id, input, actor(owner))));
      assertEquals("已完成任务不可改负责人或截止日期，请先重开", error.getMessage());
    }
    assertThrows(
        ApiException.class,
        () ->
            tx(
                () ->
                    tasks.save(
                        id,
                        new TaskController.Input("标题", "说明", "2035-01-01", List.of(peer)),
                        actor(unrelated))));
    db.update("UPDATE tasks SET status='CANCELLED' WHERE id=?", id);
    assertEquals(
        "已取消任务不可修改",
        assertThrows(
                ApiException.class,
                () ->
                    tx(
                        () ->
                            tasks.save(
                                id,
                                new TaskController.Input("标题", "说明", "2035-01-01", List.of(peer)),
                                actor(admin))))
            .getMessage());
  }

  @Test
  void activeAndNewTasksContinueRejectingDisabledAssignees() {
    long id = completedTask();
    db.update("UPDATE tasks SET status='TODO' WHERE id=?", id);
    db.update("UPDATE task_assignees SET user_id=? WHERE task_id=?", actual, id);
    for (Long taskId : Arrays.asList(id, null)) {
      assertEquals(
          "负责人必须为在职员工",
          assertThrows(
                  ApiException.class,
                  () ->
                      tx(
                          () ->
                              tasks.save(
                                  taskId,
                                  new TaskController.Input("标题", "说明", null, List.of(peer)),
                                  actor(owner))))
              .getMessage());
    }
  }
}
