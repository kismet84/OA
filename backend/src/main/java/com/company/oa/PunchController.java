package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class PunchService {
  static final String MAX_ACCURACY_METERS = "200";
  static final Duration MAX_LOCATION_AGE = Duration.ofMinutes(5);
  static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(2);
  static final String UPGRADE_MESSAGE = "定位来源或采集时间缺失或不支持，请升级 App 或改用员工网页";
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
      @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax(value = MAX_ACCURACY_METERS, message = "定位精度须大于0且不超过200米") Double accuracy,
      @Size(max = 200) String ssid,
      @NotNull(message = UPGRADE_MESSAGE) OffsetDateTime locatedAt,
      @NotNull(message = UPGRADE_MESSAGE)
      @Pattern(regexp = "LIVE|WARMUP_CACHE|LAST_KNOWN|STALE_FALLBACK", message = UPGRADE_MESSAGE)
          String locationSource) {
    Punch(String kind, Double latitude, Double longitude, Double accuracy, String ssid) {
      this(kind, latitude, longitude, accuracy, ssid, null, null);
    }
  }

  @Transactional
  long record(Actor actor, long user, Punch input, String ip, boolean simulated) {
    return recordAt(actor,user,input,ip,simulated,null);
  }

  @org.springframework.transaction.annotation.Transactional
  long recordAt(Actor actor,long user,Punch input,String ip,boolean simulated,java.time.LocalDateTime simulatedAt) {
    ApiException.require(simulated || simulatedAt==null,"仅模拟打卡可指定时间");
    ApiException.require(simulatedAt==null || !simulatedAt.isAfter(java.time.LocalDateTime.now(clock)),"模拟时间不得在未来");
    if (user != actor.id()) actor.requireAdmin();
    db.queryForList("SELECT pg_advisory_xact_lock(20261112,hashint8(?))", user);
    db.queryForList("SELECT id FROM users WHERE id=? FOR NO KEY UPDATE", user);
    auth.user(user); // Disabled accounts cannot punch, including in the simulator.
    ApiException.require(
        Double.isFinite(input.latitude())
            && Double.isFinite(input.longitude())
            && Double.isFinite(input.accuracy()),
        "必须成功获取有效定位");
    String source = input.locationSource();
    ApiException.require(source != null && Set.of("LIVE", "WARMUP_CACHE", "LAST_KNOWN", "STALE_FALLBACK").contains(source)
        && input.locatedAt() != null, UPGRADE_MESSAGE);
    ApiException.require(input.accuracy() > 0 && input.accuracy() <= Double.parseDouble(MAX_ACCURACY_METERS),
        "定位精度须大于0且不超过200米");
    var age = Duration.between(input.locatedAt().toInstant(), clock.instant());
    ApiException.require(age.compareTo(FUTURE_TOLERANCE.negated()) >= 0 && age.compareTo(MAX_LOCATION_AGE) <= 0,
        "定位采集时间须在5分钟内，且不得超过未来2分钟，请重新定位");
    ApiException.require(!source.equals("STALE_FALLBACK") || input.kind().equals("NORMAL"), "外出打卡不允许使用超时旧缓存");
    LocalDateTime now = simulatedAt==null?LocalDateTime.now(clock):simulatedAt;
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
                + " punches(user_id,punched_at,kind,latitude,longitude,accuracy,ssid,exit_ip,simulated,located_at,location_source)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
            Long.class,
            user,
            now,
            input.kind(),
            input.latitude(),
            input.longitude(),
            input.accuracy(),
            input.ssid(),
            ip,
            simulated,
            input.locatedAt(),
            source);
    if (simulated) auth.audit(actor.id(), "SIMULATE_PUNCH", id);
    return id;
  }
}

@RestController
@RequestMapping("/api/punches")
class PunchController {
  final PunchService punches;
  final JdbcTemplate db;
  final ClientIpResolver clientIps;
  final WorkbenchController workbench;

  PunchController(
      PunchService punches,
      JdbcTemplate db,
      WorkbenchController workbench,
      ClientIpResolver clientIps) {
    this.punches = punches;
    this.workbench = workbench;
    this.db = db;
    this.clientIps = clientIps;
  }

  @PostMapping
  Object punch(@Valid @RequestBody PunchService.Punch in, HttpServletRequest req) {
    String ip = clientIps.resolve(req, true);
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
    var rows =
        db.queryForList(q.sql() + " ORDER BY p.id DESC LIMIT 200 OFFSET ?", q.params().toArray());
    rows.forEach(
        row -> {
          row.put("locatedAt", row.get("located_at"));
          row.put("locationSource", row.get("location_source"));
        });
    return rows;
  }

  RequestController.Query query(
      Actor actor, Long userId, LocalDate from, LocalDate to, String kind) {
    if (!actor.admin()
        && userId != null
        && userId != actor.id()
        && !workbench.scope(actor, null, true).contains(userId))
      throw new ApiException(403, "无权查看他人打卡记录");
    if (!actor.admin() && userId == null) userId = actor.id();
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
      @NotNull Long userId, @NotNull @Valid PunchService.Punch punch, @NotBlank String exitIp, LocalDateTime at) {}

  @PostMapping("/punch")
  Object punch(@Valid @RequestBody Simulation in, HttpServletRequest req) {
    ApiException.require(AdminController.validIp(in.exitIp()), "请输入合法的出口 IP");
    return Map.of(
        "id",
        punches.recordAt(
            Auth.actor(req),
            in.userId(),
            in.punch(),
            AdminController.normalizeIp(in.exitIp()),
            true,in.at()));
  }
}
