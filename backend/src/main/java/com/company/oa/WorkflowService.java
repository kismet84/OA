package com.company.oa;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
  @org.springframework.beans.factory.annotation.Autowired
  NotificationService notifications;
  final JdbcTemplate db;
  final Auth auth;
  final Clock clock;
  @org.springframework.beans.factory.annotation.Value("${oa.uploads}")
  String uploads;

  WorkflowService(JdbcTemplate db, Auth auth, Clock clock) {
    this.db = db;
    this.auth = auth;
    this.clock = clock;
  }

  public record Application(
      Long userId,
      @NotNull @Pattern(regexp = "LEAVE|TRAVEL|OUTING|EXPENSE|OVERTIME|CORRECTION") String kind,
      Long typeId,
      LocalDateTime startAt,
      LocalDateTime endAt,
      @DecimalMin("0.01") @DecimalMax("99999999") @Digits(integer = 8, fraction = 2) BigDecimal amount,
      @NotBlank @Size(max = 2000) String reason,
      UUID attachmentId,
      @Size(max = 100) List<@NotNull @Valid ExpenseItem> items,
      @Size(max = 30) List<@NotNull UUID> attachmentIds,
      @Size(max = 100) List<@NotNull Long> ccUserIds,
      @Pattern(regexp = "NORMAL|FIELD") String punchKind) {}

  public record ExpenseItem(
      @NotBlank @Size(max = 200) String description,
      @NotNull @DecimalMin("0.01") @DecimalMax("99999999") @Digits(integer = 8, fraction = 2) BigDecimal amount) {}

  public static BigDecimal days(LocalDateTime start, LocalDateTime end) {
    return calendarDays(start, end, false);
  }

  static BigDecimal calendarDays(LocalDateTime start, LocalDateTime end, boolean crossYear) {
    ApiException.require(start != null && end != null && end.isAfter(start), "起止时间无效");
    ApiException.require(crossYear || start.getYear() == end.getYear(), "跨年申请请按年度拆分");
    ApiException.require(
        (start.toLocalTime().equals(LocalTime.of(9, 0))
                || start.toLocalTime().equals(LocalTime.of(13, 0)))
            && (end.toLocalTime().equals(LocalTime.of(13, 0))
                || end.toLocalTime().equals(LocalTime.of(18, 0))),
        "开始时间请选择 09:00 或 13:00，结束时间请选择 13:00 或 18:00");
    long halves =
        ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) * 2
            + (end.getHour() == 18 ? 2 : 1)
            - (start.getHour() == 13 ? 1 : 0);
    return BigDecimal.valueOf(halves).divide(BigDecimal.valueOf(2));
  }

  @Transactional
  public long submit(Application in, Actor actor) {
    return submit(in, actor, null);
  }

  @Transactional
  public long submit(Application in, Actor actor, String key) {
    ApiException.require(key == null || key.matches("[A-Za-z0-9_-]{8,64}"), "幂等键须为8–64位字母、数字、下划线或短横线");
    ApprovalSupport.organizationSnapshot(db);
    long user = in.userId() == null ? actor.id() : in.userId();
    if (user != actor.id()) actor.requireAdmin();
    var u = lockedUser(user);
    ApiException.require((boolean) u.get("enabled"), "申请人已停用");
    // The owner lock serializes same-user replays before validation, side effects or file claims.
    if (key != null) {
      var existing = db.queryForList("SELECT id FROM requests WHERE user_id=? AND client_request_id=?", user, key);
      if (!existing.isEmpty()) return ((Number) existing.getFirst().get("id")).longValue();
    }
    var files = new TreeSet<UUID>();
    if (in.attachmentId() != null) files.add(in.attachmentId());
    if (in.kind().equals("EXPENSE") && in.attachmentIds() != null) files.addAll(in.attachmentIds());
    for (UUID file : files) AttachmentClaims.check(db, file, actor.id(), "REQUEST", null);
    var cc = in.ccUserIds() == null ? Set.<Long>of() : new LinkedHashSet<>(in.ccUserIds());
    for (long recipient : cc) {
      ApiException.require(recipient != user, "抄送人不能是申请人本人");
      ApiException.require(db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, recipient) == 1, "抄送人不存在或已停用");
    }
    var config = db.queryForMap("SELECT * FROM workflow_config WHERE id=1");
    boolean correction = in.kind().equals("CORRECTION");
    boolean overtime = in.kind().equals("OVERTIME");
    BigDecimal duration = null;
    Map<String, Object> type = null;
    if (correction) {
      ApiException.require(in.startAt() != null && in.punchKind() != null, "请选择补卡时间和类型");
      validateCorrectionTime(in.startAt(), LocalDateTime.now(clock), ((Number) config.get("correction_days")).intValue());
      checkCorrection(user, in.startAt(), in.punchKind(), 0);
    } else if (!in.kind().equals("EXPENSE")) {
      duration = calendarDays(in.startAt(), in.endAt(), overtime);
      ApiException.require(overtime || in.endAt().isAfter(LocalDateTime.now(clock)), "结束时间必须晚于当前时间");
      ApiException.require(duration.compareTo(new BigDecimal("366")) <= 0, "申请时间过长");
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM requests WHERE user_id=? AND status IN"
                      + " ('PENDING','APPROVED') AND "
                      + (overtime ? "kind IN ('LEAVE','OVERTIME')" : in.kind().equals("LEAVE") ? "kind IN ('LEAVE','TRAVEL','OUTING','OVERTIME')" : "kind IN ('LEAVE','TRAVEL','OUTING')") + " AND"
                      + " start_at<? AND end_at>?",
                  Long.class,
                  user,
                  in.endAt(),
                  in.startAt())
              == 0,
          "与现有待审批或已批准的时段重叠");
    } else {
      ApiException.require(in.amount() != null, "报销金额不能为空");
      ApiException.require(in.items() != null && !in.items().isEmpty(), "报销至少需要一行费用明细");
      BigDecimal sum = in.items().stream().map(ExpenseItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
      ApiException.require(sum.compareTo(in.amount()) == 0, "费用明细合计必须等于申报总额");
      ApiException.require(in.attachmentIds() != null && !in.attachmentIds().isEmpty(), "报销至少需要一张有效票据");
      ApiException.require(new HashSet<>(in.attachmentIds()).size() == in.attachmentIds().size(), "票据不可重复");
      for (UUID attachment : in.attachmentIds()) {
        ApiException.require(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(uploads).resolve(attachment.toString())), "票据文件缺失");
        ApiException.require(db.queryForObject("SELECT count(*) FROM attachments WHERE id=? AND user_id=?", Long.class, attachment, actor.id()) == 1, "票据不存在或无权使用");
      }
    }
    if (in.attachmentId() != null)
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM attachments WHERE id=? AND user_id=?",
                  Long.class,
                  in.attachmentId(),
                  actor.id())
              == 1,
          "证明文件不存在或无权使用");
    if (in.kind().equals("LEAVE")) {
      ApiException.require(in.typeId() != null, "请选择假期类型");
      var types = db.queryForList("SELECT * FROM leave_types WHERE id=? AND enabled", in.typeId());
      ApiException.require(!types.isEmpty(), "假期类型已停用或不存在");
      type = types.getFirst();
      ApiException.require(
          !(boolean) type.get("proof_required") || in.attachmentId() != null, "该假期必须上传证明");
      checkQuota(
          user,
          in.typeId(),
          in.startAt().getYear(),
          duration,
          (boolean) type.get("deduct_balance"),
          (BigDecimal) type.get("annual_limit"));
    }
    Long manager = (Long) u.get("manager_id");
    Long head =
        db.queryForObject(
            "SELECT head_id FROM departments WHERE id=?", Long.class, u.get("department_id"));
    Long first = manager == null ? head : manager;
    if (correction && first == null) {
      var admins = db.queryForList("SELECT id FROM users WHERE enabled AND 'ADMIN'=ANY(string_to_array(roles,',')) AND id<>? ORDER BY id LIMIT 1", user);
      first = admins.isEmpty() ? null : ((Number) admins.getFirst().get("id")).longValue();
    }
    ApiException.require(first != null && first != user, "请管理员配置有效的直属上级或部门负责人，禁止自批");
    ApiException.require(
        auth.user(first).has("SUPERVISOR") || auth.user(first).has("DEPT_HEAD") || (correction && auth.user(first).admin()), "第一审批人角色无效");
    List<Long> chain = new ArrayList<>();
    chain.add(first);
    if (in.kind().equals("EXPENSE")) {
      if (in.amount().compareTo((BigDecimal) config.get("expense_threshold")) > 0) {
        Long finance = (Long) config.get("finance_id");
        ApiException.require(finance != null && finance != user, "请配置财务或行政审批人，禁止自批");
        ApiException.require(auth.user(finance).has("FINANCE"), "财务或行政审批人角色无效");
        if (!chain.contains(finance)) chain.add(finance);
      }
    } else if (!correction) {
      BigDecimal threshold =
          (BigDecimal)
              config.get((in.kind().equals("LEAVE") || overtime) ? "leave_threshold" : "travel_threshold");
      // A supervisor who is already any department head is sufficient; no second signature.
      if (duration.compareTo(threshold) > 0
          && head != null
          && !auth.user(first).has("DEPT_HEAD")
          && !chain.contains(head)) {
        ApiException.require(head != user && auth.user(head).has("DEPT_HEAD"), "部门负责人不可自批或角色无效");
        chain.add(head);
      }
    }
    Long id =
        db.queryForObject(
            "INSERT INTO"
                + " requests(user_id,kind,type_id,start_at,end_at,days,amount,reason,attachment_id,deduct_balance,annual_limit,client_request_id)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
            Long.class,
            user,
            in.kind(),
            type == null ? null : in.typeId(),
            in.kind().equals("EXPENSE") ? null : in.startAt(),
            in.kind().equals("EXPENSE") || correction ? null : in.endAt(),
            duration,
            in.kind().equals("EXPENSE") ? in.amount() : null,
            in.reason(),
            in.attachmentId(),
            type != null && (boolean) type.get("deduct_balance"),
            type == null ? null : type.get("annual_limit"),
            key);
    if (correction) db.update("UPDATE requests SET punch_kind=? WHERE id=?", in.punchKind(), id);
    if (overtime) db.update("UPDATE requests SET overtime_ratio=?,credited_days=? WHERE id=?", config.get("overtime_ratio"), duration.multiply((BigDecimal) config.get("overtime_ratio")), id);
    if (in.kind().equals("EXPENSE")) {
      for (int n = 0; n < in.items().size(); n++) {
        var item = in.items().get(n);
        db.update("INSERT INTO expense_items VALUES(?,?,?,?)", id, n + 1, item.description(), item.amount());
      }
      for (int n = 0; n < in.attachmentIds().size(); n++)
        db.update("INSERT INTO expense_attachments VALUES(?,?,?)", id, in.attachmentIds().get(n), n + 1);
    }
    for (int n = 0; n < chain.size(); n++)
      db.update(
          "INSERT INTO approval_steps(request_id,step,approver_id) VALUES(?,?,?)",
          id,
          n + 1,
          chain.get(n));
    for (long recipient : cc) {
      db.update("INSERT INTO request_cc VALUES(?,?)", id, recipient);
      notifications.write(recipient, "审批单 #" + id + " 抄送给您", "request_id", id);
    }
    notify(id, chain.getFirst(), "审批单 #" + id + " 待您处理");
    auth.audit(actor.id(), "SUBMIT_REQUEST", id);
    return id;
  }

  Map<String, Object> lockedUser(long user) {
    var rows = db.queryForList("SELECT * FROM users WHERE id=? FOR NO KEY UPDATE", user);
    ApiException.require(!rows.isEmpty(), "申请人不存在");
    return rows.getFirst();
  }

  void checkQuota(
      long user, long type, int year, BigDecimal days, boolean deduct, BigDecimal limit) {
    if (deduct) {
      var balance =
          db.queryForList(
              "SELECT amount FROM balances WHERE user_id=? AND type_id=? AND year=? FOR UPDATE",
              user,
              type,
              year);
      ApiException.require(
          !balance.isEmpty()
              && ((BigDecimal) balance.getFirst().get("amount")).compareTo(days) >= 0,
          "可用余额不足，请联系管理员录入额度");
    }
    if (limit != null) {
      BigDecimal used =
          db.queryForObject(
              "SELECT coalesce(sum(days),0) FROM requests WHERE user_id=? AND type_id=? AND"
                  + " status='APPROVED' AND extract(year FROM start_at)=?",
              BigDecimal.class,
              user,
              type,
              year);
      ApiException.require(used.add(days).compareTo(limit) <= 0, "超过该假期年度可休天数");
    }
  }

  Map<String, Object> get(long id, Actor a, boolean lock) {
    var rows =
        db.queryForList("SELECT * FROM requests WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw new ApiException(404, "审批单不存在");
    var r = rows.getFirst();
    var params = new ArrayList<Object>();
    params.add(id);
    String visible = RequestVisibility.sql(a, params);
    if (db.queryForObject("SELECT count(*) FROM requests r WHERE r.id=? AND " + visible,
        Long.class, params.toArray()) == 0)
      throw new ApiException(403, "无权查看该审批单");
    return r;
  }

  public record Decision(boolean approve, @Size(max = 1000) String comment) {}

  @Transactional
  public void decide(long id, Decision in, Actor actor) {
    db.queryForList("SELECT pg_advisory_xact_lock(20261113,hashint8(?))",id);
    var r = get(id, actor, true);
    ApiException.require(r.get("status").equals("PENDING"), "该单据已处理");
    long owner = ((Number) r.get("user_id")).longValue();
    ApiException.require(owner != actor.id(), "不能审批自己的申请");
    var step =
        db.queryForMap(
            "SELECT * FROM approval_steps WHERE request_id=? AND step=?",
            id,
            r.get("current_step"));
    if (!actor.admin() && ((Number) step.get("approver_id")).longValue() != actor.id())
      throw new ApiException(403, "当前不轮到您审批");
    if (!actor.admin()) auth.user(((Number) step.get("approver_id")).longValue());
    ApiException.require(
        in.approve() || (in.comment() != null && !in.comment().isBlank()), "驳回必须填写意见");
    lockedUser(owner);
    int next = (int) r.get("current_step") + 1;
    var nextSteps =
        db.queryForList(
            "SELECT approver_id FROM approval_steps WHERE request_id=? AND step=?", id, next);
    boolean finalApproval = in.approve() && nextSteps.isEmpty();
    if (finalApproval && r.get("kind").equals("LEAVE")) {
      long type = ((Number) r.get("type_id")).longValue();
      int year = ((Timestamp) r.get("start_at")).toLocalDateTime().getYear();
      checkQuota(
          owner,
          type,
          year,
          (BigDecimal) r.get("days"),
          (boolean) r.get("deduct_balance"),
          (BigDecimal) r.get("annual_limit"));
      if ((boolean) r.get("deduct_balance"))
        changeBalance(owner, type, year, ((BigDecimal) r.get("days")).negate(), id, actor.id(), "LEAVE_APPROVED");
    }
    if (finalApproval && r.get("kind").equals("CORRECTION")) {
      var at = ((Timestamp) r.get("start_at")).toLocalDateTime();
      checkCorrection(owner, at, (String) r.get("punch_kind"), id);
      db.update("INSERT INTO punches(user_id,punched_at,kind,correction_request_id,location_source) VALUES(?,?,?,?,'CORRECTION')", owner, at, r.get("punch_kind"), id);
    }
    if (finalApproval && r.get("kind").equals("OVERTIME")) {
      changeBalance(owner, compType(), ((Timestamp) r.get("start_at")).toLocalDateTime().getYear(), (BigDecimal) r.get("credited_days"), id, actor.id(), "OVERTIME_APPROVED");
    }
    db.update(
        "UPDATE approval_steps SET status=?,comment=?,actor_id=?,acted_at=? WHERE request_id=? AND step=?",
        in.approve() ? "APPROVED" : "REJECTED",
        actor.admin()?ApprovalSupport.proxyNote(in.comment()):in.comment(),
        actor.id(),
        LocalDateTime.now(clock),
        id,
        r.get("current_step"));
    String status = !in.approve() ? "REJECTED" : finalApproval ? "APPROVED" : "PENDING";
    db.update(
        "UPDATE requests SET status=?,current_step=? WHERE id=?",
        status,
        status.equals("PENDING") ? next : r.get("current_step"),
        id);
    if (finalApproval && r.get("kind").equals("EXPENSE"))
      db.update("UPDATE requests SET payment_status='UNPAID' WHERE id=?", id);
    if (status.equals("PENDING"))
      notify(id,
          ((Number) nextSteps.getFirst().get("approver_id")).longValue(), "审批单 #" + id + " 待您处理");
    else notify(id, owner, "审批单 #" + id + (in.approve() ? " 已批准" : " 已驳回"));
    if (!status.equals("PENDING"))
      for (long recipient : db.queryForList("SELECT user_id FROM request_cc WHERE request_id=?", Long.class, id))
        notifications.write(recipient, "抄送审批单 #" + id + (in.approve() ? " 已批准" : " 已驳回"), "request_id", id);
    auth.audit(actor.id(), in.approve() ? "APPROVE_REQUEST" : "REJECT_REQUEST", id);
  }

  @Transactional
  public void reassign(long id, ApprovalSupport.Reassign in, Actor actor) {
    actor.requireAdmin();ApprovalSupport.organizationSnapshot(db);
    var r=get(id,actor,true);
    ApiException.require(r.get("status").equals("PENDING"),"仅待审批申请可改派");
    var s=db.queryForMap("SELECT * FROM approval_steps WHERE request_id=? AND step=?",id,r.get("current_step"));
    ApprovalSupport.reassign(db,actor,"REQUEST",id,0,((Number)r.get("current_step")).intValue(),(Long)s.get("approver_id"),((Number)r.get("user_id")).longValue(),in);
    db.update("UPDATE approval_steps SET approver_id=? WHERE request_id=? AND step=?",in.approverId(),id,r.get("current_step"));
    notifications.write(in.approverId(),"审批单 #"+id+" 已改派给您审批："+in.note(),"request_id",id);
    auth.audit(actor.id(),"REASSIGN_REQUEST",id);
  }

  public record Payment(@NotNull @Pattern(regexp = "PAID|UNPAID") String status,
      @Size(max = 1000) String reason) {}

  @Transactional
  public void payment(long id, Payment in, Actor actor) {
    if (!actor.admin() && !actor.has("FINANCE")) throw new ApiException(403, "仅财务或管理员可标记支付状态");
    var r = get(id, actor, true);
    ApiException.require(r.get("kind").equals("EXPENSE") && r.get("status").equals("APPROVED"), "仅已批准报销可标记支付状态");
    ApiException.require(!in.status().equals(r.get("payment_status")), "支付状态未变化");
    ApiException.require(!in.status().equals("UNPAID") || (in.reason() != null && !in.reason().isBlank()), "改回待支付必须填写原因");
    var now = LocalDateTime.now(clock);
    db.update("UPDATE requests SET payment_status=?,payment_actor_id=?,payment_acted_at=? WHERE id=?", in.status(), actor.id(), now, id);
    db.update("INSERT INTO expense_payment_history(request_id,status,actor_id,acted_at,reason) VALUES(?,?,?,?,?)", id, in.status(), actor.id(), now, in.reason());
    auth.audit(actor.id(), in.status().equals("PAID") ? "MARK_EXPENSE_PAID" : "RESET_EXPENSE_UNPAID", id);
  }

  @Transactional
  public void cancel(long id, Actor actor) {
    db.queryForList("SELECT pg_advisory_xact_lock(20261113,hashint8(?))",id);
    var r = get(id, actor, true);
    long owner = ((Number) r.get("user_id")).longValue();
    if (owner != actor.id()) actor.requireAdmin();
    String status = (String) r.get("status");
    ApiException.require(status.equals("PENDING") || status.equals("APPROVED"), "该状态不可撤回或撤销");
    if (status.equals("APPROVED")) {
      ApiException.require(!r.get("kind").equals("CORRECTION"), "补卡已写入打卡记录，不可撤销");
      ApiException.require(
          r.get("start_at") != null
              && ((Timestamp) r.get("start_at"))
                  .toLocalDateTime()
                  .isAfter(LocalDateTime.now(clock)),
          "申请已开始或报销已批准，不可撤销");
      lockedUser(owner);
      int year = ((Timestamp) r.get("start_at")).toLocalDateTime().getYear();
      if ((boolean) r.get("deduct_balance"))
        changeBalance(owner, ((Number) r.get("type_id")).longValue(), year, (BigDecimal) r.get("days"), id, actor.id(), "LEAVE_CANCELLED");
      if (r.get("kind").equals("OVERTIME"))
        changeBalance(owner, compType(), year, ((BigDecimal) r.get("credited_days")).negate(), id, actor.id(), "OVERTIME_CANCELLED");
    }
    for (var s :
        db.queryForList(
            "SELECT approver_id recipient_id FROM approval_steps WHERE request_id=? AND"
                + " status='APPROVED' UNION SELECT actor_id recipient_id FROM audit_logs WHERE"
                + " action='APPROVE_REQUEST' AND target=? AND actor_id IS NOT NULL",
            id,
            String.valueOf(id)))
      notify(id, ((Number) s.get("recipient_id")).longValue(), "审批单 #" + id + " 已由申请人或管理员撤销/撤回");
    db.update(
        "UPDATE requests SET status=? WHERE id=?",
        status.equals("PENDING") ? "WITHDRAWN" : "CANCELLED",
        id);
    auth.audit(actor.id(), "CANCEL_REQUEST", id);
  }

  static void validateCorrectionTime(LocalDateTime at, LocalDateTime now, int deadlineDays) {
    ApiException.require(!at.isAfter(now), "不能为未来时间补卡");
    ApiException.require(!now.toLocalDate().isAfter(at.toLocalDate().plusDays(deadlineDays)), "已超过补卡期限");
  }

  void checkCorrection(long user, LocalDateTime at, String kind, long exclude) {
    ApiException.require(db.queryForObject("SELECT count(*) FROM punches WHERE user_id=? AND punched_at=? AND kind=?", Long.class, user, at, kind) == 0, "该时刻已有同类型打卡记录");
    ApiException.require(db.queryForObject("SELECT count(*) FROM requests WHERE user_id=? AND kind='CORRECTION' AND start_at=? AND punch_kind=? AND status IN ('PENDING','APPROVED') AND id<>?", Long.class, user, at, kind, exclude) == 0, "该时刻已有待批或已批准的补卡");
    if (kind.equals("FIELD"))
      ApiException.require(db.queryForObject("SELECT count(*) FROM requests WHERE user_id=? AND kind IN ('TRAVEL','OUTING') AND status='APPROVED' AND start_at<=? AND end_at>?", Long.class, user, at, at) > 0, "补卡时刻没有有效的已批准出差或外出申请");
  }

  long compType() {
    return db.queryForObject("SELECT id FROM leave_types WHERE code='COMP'", Long.class);
  }

  // Caller locks the owner first. Balance row locks also protect concurrent quota changes.
  void changeBalance(long user, long type, int year, BigDecimal delta, long request, long actor, String reason) {
    db.update("INSERT INTO balances(user_id,type_id,year,amount) VALUES(?,?,?,0) ON CONFLICT DO NOTHING", user, type, year);
    var before = db.queryForObject("SELECT amount FROM balances WHERE user_id=? AND type_id=? AND year=? FOR UPDATE", BigDecimal.class, user, type, year);
    var after = before.add(delta);
    ApiException.require(after.signum() >= 0, "调休余额不足，无法退回已兑换额度，请先处理已使用的调休");
    db.update("UPDATE balances SET amount=? WHERE user_id=? AND type_id=? AND year=?", after, user, type, year);
    db.update("INSERT INTO balance_changes(user_id,type_id,year,delta,balance_after,request_id,actor_id,reason) VALUES(?,?,?,?,?,?,?,?)", user, type, year, delta, after, request, actor, reason);
  }

  void notify(long requestId, long user, String message) {
    notifications.write(user, message, "request_id", requestId);
  }
}
