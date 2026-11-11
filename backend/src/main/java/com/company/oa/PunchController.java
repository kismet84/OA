package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class PunchService {
  final JdbcTemplate db;
  final Clock clock;
  final Auth auth;

  PunchService(JdbcTemplate db, Clock clock, Auth auth) {
    this.db = db;
    this.clock = clock;
    this.auth = auth;
  }

  record Punch(
      @NotNull @Pattern(regexp = "NORMAL|FIELD") String kind,
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
      @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") Double accuracy,
      @Size(max = 200) String ssid) {}

  @Transactional
  long record(Actor actor, long user, Punch input, String ip, boolean simulated) {
    if (user != actor.id()) actor.requireAdmin();
    db.execute("SELECT pg_advisory_xact_lock(20261111)");
    db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", user);
    auth.user(user); // Disabled accounts cannot punch, including in the simulator.
    ApiException.require(
        Double.isFinite(input.latitude())
            && Double.isFinite(input.longitude())
            && Double.isFinite(input.accuracy()),
        "必须成功获取有效定位");
    LocalDateTime now = LocalDateTime.now(clock);
    if (input.kind().equals("NORMAL")) {
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM networks WHERE (kind='IP' AND value=?) OR (kind='SSID' AND"
                      + " value=?)",
                  Long.class,
                  ip,
                  input.ssid())
              > 0,
          "不在登记的公司网络，请连接公司网络或提交有效的出差/外出申请");
    } else {
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM requests WHERE user_id=? AND kind IN ('TRAVEL','OUTING')"
                      + " AND status='APPROVED' AND start_at<=? AND end_at>?",
                  Long.class,
                  user,
                  now,
                  now)
              > 0,
          "当前没有有效的已批准出差或外出申请，不能打出勤卡");
    }
    Long id =
        db.queryForObject(
            "INSERT INTO"
                + " punches(user_id,punched_at,kind,latitude,longitude,accuracy,ssid,exit_ip,simulated)"
                + " VALUES(?,?,?,?,?,?,?,?,?) RETURNING id",
            Long.class,
            user,
            now,
            input.kind(),
            input.latitude(),
            input.longitude(),
            input.accuracy(),
            input.ssid(),
            ip,
            simulated);
    if (simulated) auth.audit(actor.id(), "SIMULATE_PUNCH", id);
    return id;
  }
}

@RestController
@RequestMapping("/api/punches")
class PunchController {
  final PunchService punches;
  final JdbcTemplate db;
  final Set<String> trustedProxies;

  PunchController(
      PunchService punches, JdbcTemplate db, @Value("${oa.trusted-proxies:}") String proxies) {
    this.punches = punches;
    this.db = db;
    this.trustedProxies = new HashSet<>(Arrays.asList(proxies.split(",")));
  }

  @PostMapping
  Object punch(@Valid @RequestBody PunchService.Punch in, HttpServletRequest req) {
    String ip = req.getRemoteAddr();
    if (trustedProxies.contains(ip)) {
      String forwarded = req.getHeader("X-OA-Client-IP");
      ApiException.require(AdminController.validIp(forwarded), "可信反向代理必须传递真实客户端 IP");
      ip = forwarded;
    }
    return Map.of(
        "id",
        punches.record(
            Auth.actor(req), Auth.actor(req).id(), in, AdminController.normalizeIp(ip), false));
  }

  @GetMapping
  Object list(
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    var q = query(Auth.actor(req), userId, from, to, kind);
    q.params().add(Math.max(0, offset));
    return db.queryForList(
        q.sql() + " ORDER BY p.id DESC LIMIT 200 OFFSET ?", q.params().toArray());
  }

  RequestController.Query query(
      Actor actor, Long userId, LocalDate from, LocalDate to, String kind) {
    if (!actor.admin() && userId != null && userId != actor.id())
      throw new ApiException(403, "无权查看他人打卡记录");
    if (!actor.admin()) userId = actor.id();
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.*,u.name user_name,u.employee_no FROM punches p JOIN users u ON"
                + " u.id=p.user_id WHERE 1=1");
    List<Object> params = new ArrayList<>();
    if (userId != null) {
      sql.append(" AND p.user_id=?");
      params.add(userId);
    }
    if (from != null) {
      sql.append(" AND p.punched_at>=?");
      params.add(from.atStartOfDay());
    }
    if (to != null) {
      sql.append(" AND p.punched_at<?");
      params.add(to.plusDays(1).atStartOfDay());
    }
    if (!kind.isBlank()) {
      sql.append(" AND p.kind=?");
      params.add(kind);
    }
    return new RequestController.Query(sql.toString(), params);
  }
}

@RestController
@Profile("dev")
@RequestMapping("/api/admin/dev")
@AdminOnly
class DevPunchController {
  final PunchService punches;

  DevPunchController(PunchService punches) {
    this.punches = punches;
  }

  record Simulation(
      @NotNull Long userId, @NotNull @Valid PunchService.Punch punch, @NotBlank String exitIp) {}

  @PostMapping("/punch")
  Object punch(@Valid @RequestBody Simulation in, HttpServletRequest req) {
    ApiException.require(AdminController.validIp(in.exitIp()), "请输入合法的出口 IP");
    return Map.of(
        "id",
        punches.record(
            Auth.actor(req),
            in.userId(),
            in.punch(),
            AdminController.normalizeIp(in.exitIp()),
            true));
  }
}
