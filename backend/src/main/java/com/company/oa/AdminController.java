package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@AdminOnly
class AdminController {
  final JdbcTemplate db;
  final Auth auth;
  static final Set<String> ROLES =
      Set.of("EMPLOYEE", "SUPERVISOR", "DEPT_HEAD", "ADMIN", "FINANCE");

  AdminController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  void orgLock() {
    db.execute("SELECT pg_advisory_xact_lock(20261111)");
  }

  void active(Long id) {
    if (id != null)
      ApiException.require(
          db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, id)
              == 1,
          "关联人员不存在或已停用");
  }

  void role(Long id, String role) {
    active(id);
    if (id != null)
      ApiException.require(
          auth.user(id).has(role),
          "关联人员必须具备对应角色：" + (role.equals("DEPT_HEAD") ? "部门负责人" : "财务/行政"));
  }

  @GetMapping("/users")
  Object users() {
    return db.queryForList(
        "SELECT"
            + " u.id,u.employee_no,u.phone,u.name,u.roles,u.department_id,u.manager_id,u.enabled,u.must_change_password,d.name"
            + " department_name FROM users u JOIN departments d ON d.id=u.department_id ORDER BY"
            + " u.id");
  }

  @GetMapping("/departments")
  Object departments() {
    return db.queryForList("SELECT * FROM departments ORDER BY id");
  }

  record Department(@NotBlank @Size(max = 100) String name, Long parentId, Long headId) {}

  @PostMapping("/departments")
  @Transactional
  Object createDepartment(@Valid @RequestBody Department input, HttpServletRequest req) {
    return saveDepartment(null, input, Auth.actor(req));
  }

  @PutMapping("/departments/{id}")
  @Transactional
  Object updateDepartment(
      @PathVariable long id, @Valid @RequestBody Department input, HttpServletRequest req) {
    return saveDepartment(id, input, Auth.actor(req));
  }

  Object saveDepartment(Long id, Department in, Actor actor) {
    orgLock();
    role(in.headId(), "DEPT_HEAD");
    if (in.parentId() != null) {
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM departments WHERE id=?", Long.class, in.parentId())
              == 1,
          "上级部门不存在");
      if (id != null)
        ApiException.require(
            db.queryForObject(
                    "WITH RECURSIVE descendants AS (SELECT id FROM departments WHERE id=? UNION ALL"
                        + " SELECT d.id FROM departments d JOIN descendants p ON d.parent_id=p.id)"
                        + " SELECT count(*) FROM descendants WHERE id=?",
                    Long.class,
                    id,
                    in.parentId())
                == 0,
            "部门不能形成循环");
    }
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO departments(name,parent_id,head_id) VALUES(?,?,?) RETURNING id",
              Long.class,
              in.name(),
              in.parentId(),
              in.headId());
    else
      ApiException.require(
          db.update(
                  "UPDATE departments SET name=?,parent_id=?,head_id=? WHERE id=?",
                  in.name(),
                  in.parentId(),
                  in.headId(),
                  id)
              == 1,
          "部门不存在");
    auth.audit(actor.id(), "SAVE_DEPARTMENT", id);
    return Map.of("id", id);
  }

  record UserInput(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,40}") String employeeNo,
      @Pattern(regexp = "[+]?[0-9]{5,19}") String phone,
      @NotBlank @Size(max = 80) String name,
      @NotEmpty Set<String> roles,
      @NotNull Long departmentId,
      Long managerId,
      boolean enabled,
      @Size(min = 10, max = 72) String password) {}

  @PostMapping("/users")
  @Transactional
  Object createUser(@Valid @RequestBody UserInput in, HttpServletRequest req) {
    return saveUser(null, in, Auth.actor(req));
  }

  @PutMapping("/users/{id}")
  @Transactional
  Object updateUser(
      @PathVariable long id, @Valid @RequestBody UserInput in, HttpServletRequest req) {
    return saveUser(id, in, Auth.actor(req));
  }

  Object saveUser(Long id, UserInput in, Actor actor) {
    orgLock();
    ApiException.require(ROLES.containsAll(in.roles()), "角色无效");
    active(in.managerId());
    if (in.managerId() != null)
      ApiException.require(
          auth.user(in.managerId()).has("SUPERVISOR") || auth.user(in.managerId()).has("DEPT_HEAD"),
          "直属上级必须具有直属上级或部门负责人角色");
    ApiException.require(
        db.queryForObject(
                "SELECT count(*) FROM departments WHERE id=?", Long.class, in.departmentId())
            == 1,
        "主部门不存在");
    // Login aliases share a single namespace: a phone must never equal another account's employee
    // number.
    ApiException.require(
        db.queryForObject(
                "SELECT count(*) FROM users WHERE id<>? AND (phone=? OR employee_no=?)",
                Long.class,
                id == null ? 0 : id,
                in.employeeNo(),
                in.phone())
            == 0,
        "工号和手机号与已有登录标识冲突");
    if (id != null) {
      activeExists(id);
      if (in.managerId() != null)
        ApiException.require(
            db.queryForObject(
                    "WITH RECURSIVE managers AS (SELECT id,manager_id FROM users WHERE id=? UNION"
                        + " ALL SELECT u.id,u.manager_id FROM users u JOIN managers m ON"
                        + " u.id=m.manager_id) SELECT count(*) FROM managers WHERE id=?",
                    Long.class,
                    in.managerId(),
                    id)
                == 0,
            "直属上级不能形成循环");
      if (!in.enabled() || !in.roles().contains("ADMIN")) {
        ApiException.require(
            !Objects.equals(id, actor.id()) || (in.enabled() && in.roles().contains("ADMIN")),
            "不能停用自身或移除自身管理员角色");
        ApiException.require(
            db.queryForObject(
                    "SELECT count(*) FROM users WHERE enabled AND"
                        + " 'ADMIN'=ANY(string_to_array(roles,',')) AND id<>?",
                    Long.class,
                    id)
                > 0,
            "至少保留一个有效管理员");
      }
      if (!in.roles().contains("DEPT_HEAD") || !in.enabled())
        ApiException.require(
            db.queryForObject("SELECT count(*) FROM departments WHERE head_id=?", Long.class, id)
                == 0,
            "请先调整该人员负责的部门");
      if (!in.enabled()
          || (!in.roles().contains("SUPERVISOR") && !in.roles().contains("DEPT_HEAD")))
        ApiException.require(
            db.queryForObject(
                    "SELECT count(*) FROM users WHERE manager_id=? AND enabled", Long.class, id)
                == 0,
            "请先调整该人员的下属关系");
      if (!in.enabled() || !in.roles().contains("FINANCE"))
        ApiException.require(
            db.queryForObject(
                    "SELECT count(*) FROM workflow_config WHERE finance_id=?", Long.class, id)
                == 0,
            "请先调整财务审批人");
      if (!in.enabled())
        ApiException.require(
            db.queryForObject(
                    "SELECT count(*) FROM approval_steps s JOIN requests r ON r.id=s.request_id"
                        + " WHERE s.approver_id=? AND r.status='PENDING'",
                    Long.class,
                    id)
                == 0,
            "该人员仍有待办审批，请先处理或撤回");
    }
    String roles = String.join(",", new TreeSet<>(in.roles()));
    if (id == null) {
      ApiException.require(in.password() != null, "开通账号必须设置初始密码");
      id =
          db.queryForObject(
              "INSERT INTO"
                  + " users(employee_no,phone,name,roles,department_id,manager_id,enabled,password_hash)"
                  + " VALUES(?,?,?,?,?,?,?,?) RETURNING id",
              Long.class,
              in.employeeNo(),
              in.phone(),
              in.name(),
              roles,
              in.departmentId(),
              in.managerId(),
              in.enabled(),
              auth.encoder.encode(Auth.validPassword(in.password())));
    } else {
      db.update(
          "UPDATE users SET"
              + " employee_no=?,phone=?,name=?,roles=?,department_id=?,manager_id=?,enabled=?,session_version=session_version+1"
              + " WHERE id=?",
          in.employeeNo(),
          in.phone(),
          in.name(),
          roles,
          in.departmentId(),
          in.managerId(),
          in.enabled(),
          id);
      if (in.password() != null)
        db.update(
            "UPDATE users SET password_hash=?,must_change_password=true WHERE id=?",
            auth.encoder.encode(Auth.validPassword(in.password())),
            id);
    }
    auth.audit(actor.id(), "SAVE_USER", id);
    return Map.of("id", id);
  }

  void activeExists(long id) {
    ApiException.require(
        db.queryForObject("SELECT count(*) FROM users WHERE id=?", Long.class, id) == 1, "账号不存在");
  }

  record Network(
      @Pattern(regexp = "SSID|IP") @NotNull String kind, @NotBlank @Size(max = 200) String value) {}

  @GetMapping("/networks")
  Object networks() {
    return db.queryForList("SELECT * FROM networks ORDER BY id");
  }

  @PostMapping("/networks")
  @Transactional
  Object network(@Valid @RequestBody Network in, HttpServletRequest req) {
    if (in.kind().equals("IP")) ApiException.require(validIp(in.value()), "请输入合法的 IPv4 或 IPv6 地址");
    String value = in.kind().equals("IP") ? normalizeIp(in.value()) : in.value().trim();
    db.update("INSERT INTO networks(kind,value) VALUES(?,?)", in.kind(), value);
    auth.audit(Auth.actor(req).id(), "ADD_NETWORK", in.kind() + ":" + value);
    return Map.of("message", "已登记");
  }

  static boolean validIp(String ip) {
    if (ip == null) return false;
    if (ip.contains(":")) {
      if (!ip.matches("[0-9a-fA-F:.]+")) return false;
      try {
        InetAddress.getByName(ip);
        return true;
      } catch (Exception e) {
        return false;
      }
    }
    String[] parts = ip.split("\\.", -1);
    if (parts.length != 4) return false;
    for (String part : parts) {
      if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
    }
    return true;
  }

  static String normalizeIp(String ip) {
    try {
      return InetAddress.getByName(ip).getHostAddress();
    } catch (Exception e) {
      throw new ApiException(400, "IP 地址格式错误");
    }
  }

  @DeleteMapping("/networks/{id}")
  @Transactional
  Object deleteNetwork(@PathVariable long id, HttpServletRequest req) {
    db.update("DELETE FROM networks WHERE id=?", id);
    auth.audit(Auth.actor(req).id(), "DELETE_NETWORK", id);
    return Map.of("message", "已删除");
  }

  @GetMapping("/leave-types")
  Object leaveTypes() {
    return db.queryForList("SELECT * FROM leave_types ORDER BY id");
  }

  record LeaveType(
      @NotBlank @Pattern(regexp = "[A-Z_]{2,30}") String code,
      @NotBlank @Size(max = 60) String name,
      boolean deductBalance,
      @DecimalMin("0.5") BigDecimal annualLimit,
      boolean proofRequired,
      boolean enabled) {}

  @PostMapping("/leave-types")
  @Transactional
  Object addType(@Valid @RequestBody LeaveType in, HttpServletRequest req) {
    return saveType(null, in, Auth.actor(req));
  }

  @PutMapping("/leave-types/{id}")
  @Transactional
  Object updateType(
      @PathVariable long id, @Valid @RequestBody LeaveType in, HttpServletRequest req) {
    return saveType(id, in, Auth.actor(req));
  }

  Object saveType(Long id, LeaveType in, Actor actor) {
    if (in.annualLimit() != null) half(in.annualLimit());
    if (id != null) {
      var existing = db.queryForList("SELECT code FROM leave_types WHERE id=?", id);
      ApiException.require(!existing.isEmpty(), "假期类型不存在");
      ApiException.require(in.code().equals(existing.getFirst().get("code")), "已建立的假期英文标识不可修改");
    }
    if (Set.of("ANNUAL", "COMP").contains(in.code()))
      ApiException.require(in.deductBalance(), "年假和调休必须按余额扣减");
    if (Set.of("PERSONAL", "SICK", "MARRIAGE", "MATERNITY", "PATERNITY").contains(in.code()))
      ApiException.require(!in.deductBalance(), "该假期按年度上限管理，不扣减手工余额");
    if (Set.of("MARRIAGE", "MATERNITY", "PATERNITY").contains(in.code()))
      ApiException.require(in.proofRequired() && in.annualLimit() != null, "法定假必须配置可休天数并要求证明");
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO"
                  + " leave_types(code,name,deduct_balance,annual_limit,proof_required,enabled)"
                  + " VALUES(?,?,?,?,?,?) RETURNING id",
              Long.class,
              in.code(),
              in.name(),
              in.deductBalance(),
              in.annualLimit(),
              in.proofRequired(),
              in.enabled());
    else
      ApiException.require(
          db.update(
                  "UPDATE leave_types SET"
                      + " code=?,name=?,deduct_balance=?,annual_limit=?,proof_required=?,enabled=?"
                      + " WHERE id=?",
                  in.code(),
                  in.name(),
                  in.deductBalance(),
                  in.annualLimit(),
                  in.proofRequired(),
                  in.enabled(),
                  id)
              == 1,
          "假期类型不存在");
    auth.audit(actor.id(), "SAVE_LEAVE_TYPE", id);
    return Map.of("id", id);
  }

  static void half(BigDecimal value) {
    ApiException.require(value.remainder(new BigDecimal("0.5")).signum() == 0, "天数必须为半天的整数倍");
  }

  record Balance(
      @NotNull Long userId,
      @NotNull Long typeId,
      @Min(2020) @Max(2100) int year,
      @NotNull @DecimalMin("0") @DecimalMax("9999") BigDecimal amount) {}

  @GetMapping("/balances")
  Object balances() {
    return db.queryForList(
        "SELECT b.*,u.name user_name,t.name type_name FROM balances b JOIN users u ON"
            + " u.id=b.user_id JOIN leave_types t ON t.id=b.type_id ORDER BY b.year"
            + " DESC,u.id,t.id");
  }

  @PutMapping("/balances")
  @Transactional
  Object balance(@Valid @RequestBody Balance in, HttpServletRequest req) {
    half(in.amount());
    db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", in.userId());
    db.update(
        "INSERT INTO balances(user_id,type_id,year,amount) VALUES(?,?,?,?) ON"
            + " CONFLICT(user_id,type_id,year) DO UPDATE SET amount=excluded.amount",
        in.userId(),
        in.typeId(),
        in.year(),
        in.amount());
    auth.audit(Auth.actor(req).id(), "SET_REMAINING_BALANCE", in.toString());
    return Map.of("message", "余额已保存");
  }

  record Workflow(
      @NotNull @DecimalMin("0.5") BigDecimal leaveThreshold,
      @NotNull @DecimalMin("0.5") BigDecimal travelThreshold,
      @NotNull @DecimalMin("0") BigDecimal expenseThreshold,
      Long financeId) {}

  @GetMapping("/workflow")
  Object workflow() {
    return db.queryForMap("SELECT * FROM workflow_config WHERE id=1");
  }

  @PutMapping("/workflow")
  @Transactional
  Object workflow(@Valid @RequestBody Workflow in, HttpServletRequest req) {
    orgLock();
    role(in.financeId(), "FINANCE");
    half(in.leaveThreshold());
    half(in.travelThreshold());
    db.update(
        "UPDATE workflow_config SET"
            + " leave_threshold=?,travel_threshold=?,expense_threshold=?,finance_id=? WHERE id=1",
        in.leaveThreshold(),
        in.travelThreshold(),
        in.expenseThreshold(),
        in.financeId());
    auth.audit(Auth.actor(req).id(), "SAVE_WORKFLOW", in.toString());
    return Map.of("message", "审批分流已保存");
  }

  @GetMapping("/audit")
  Object audit() {
    return db.queryForList(
        "SELECT a.*,u.name actor_name FROM audit_logs a LEFT JOIN users u ON u.id=a.actor_id ORDER"
            + " BY a.id DESC LIMIT 500");
  }
}
