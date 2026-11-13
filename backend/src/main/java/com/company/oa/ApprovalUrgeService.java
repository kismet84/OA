package com.company.oa;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ApprovalUrgeService {
  final JdbcTemplate db;
  final NotificationService notifications;
  final Auth auth;
  final Clock clock;

  ApprovalUrgeService(JdbcTemplate db, NotificationService n, Auth auth, Clock clock) {
    this.db = db;
    notifications = n;
    this.auth = auth;
    this.clock = clock;
  }

  record Target(
      String business,
      long id,
      long owner,
      int revision,
      int step,
      Long approver,
      Instant activated,
      String title) {}

  static int num(Map<String, Object> r, String k) {
    return ((Number) r.get(k)).intValue();
  }

  static Instant instant(Object v) {
    if (v == null) return Instant.EPOCH;
    if (v instanceof java.sql.Timestamp t) return t.toInstant();
    if (v instanceof OffsetDateTime t) return t.toInstant();
    return Instant.parse(v.toString());
  }

  Target target(String business, long id, boolean lock) {
    String table =
        switch (business) {
          case "REQUEST" -> "requests";
          case "CONTRACT" -> "contracts";
          case "ASSET" -> "asset_requests";
          default -> throw new ApiException(400, "单据类型无效");
        };
    var rows =
        db.queryForList("SELECT * FROM " + table + " WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw new ApiException(404, "单据不存在");
    var r = rows.getFirst();
    if (!"PENDING".equals(r.get("status"))) return null;
    int revision = business.equals("CONTRACT") ? num(r, "revision") : 0,
        step = business.equals("ASSET") ? 1 : num(r, "current_step");
    Map<String, Object> s;
    if (business.equals("ASSET")) s = r;
    else
      s =
          db.queryForMap(
              "SELECT * FROM "
                  + (business.equals("REQUEST")
                      ? "approval_steps WHERE request_id=? AND step=?"
                      : "contract_steps WHERE contract_id=? AND step=? AND revision=" + revision),
              id,
              step);
    String title =
        business.equals("REQUEST")
            ? ExportController.VALUES.getOrDefault(
                    r.get("kind").toString(), r.get("kind").toString())
                + "申请"
                + (r.get("start_at") == null
                    ? ""
                    : "（" + r.get("start_at") + " 至 " + r.get("end_at") + "）")
            : business.equals("CONTRACT") ? "合同：" + r.get("name") : "资产领用申请";
    return new Target(
        business,
        id,
        ((Number) r.get("user_id")).longValue(),
        revision,
        step,
        (Long) s.get("approver_id"),
        instant(s.get("activated_at")),
        title + " #" + id);
  }

  List<Map<String, Object>> recipients(Target t) {
    return db.queryForList(
        "SELECT id,name FROM users WHERE enabled AND id<>? AND "
            + (t.approver() != null ? "id=?" : "(','||roles||',') ~ ',(FINANCE|ADMIN),'"),
        t.approver() != null ? new Object[] {t.owner(), t.approver()} : new Object[] {t.owner()});
  }

  Instant last(Target t, long user, String mode, boolean cycle) {
    var args = new ArrayList<Object>(List.of(t.business(), t.id(), t.revision(), t.step(), user));
    String sql =
        "SELECT max(created_at) FROM approval_urges WHERE business=? AND target_id=? AND revision=?"
            + " AND step=? AND approver_id=?";
    if (mode != null) {
      sql += " AND mode=?";
      args.add(mode);
    }
    if (cycle) {
      sql += " AND activated_at=?";
      args.add(OffsetDateTime.ofInstant(t.activated(), ZoneOffset.UTC));
    }
    Object v = db.queryForObject(sql, Object.class, args.toArray());
    return v == null ? null : instant(v);
  }

  Map<String, Object> metadata(String business, long id, Actor actor) {
    var out = new LinkedHashMap<String, Object>();
    out.put("canUrge", false);
    out.put("lastUrgedAt", null);
    out.put("urgeAvailableAt", null);
    var t = target(business, id, false);
    if (t == null || t.owner() != actor.id()) return out;
    var users = recipients(t);
    Instant latest = null;
    for (var u : users) {
      var v = last(t, ((Number) u.get("id")).longValue(), null, false);
      if (v != null && (latest == null || v.isAfter(latest))) latest = v;
    }
    out.put("canUrge", !users.isEmpty());
    out.put("lastUrgedAt", latest);
    if (latest != null)
      out.put(
          "urgeAvailableAt",
          latest.plusSeconds(((Number) config().get("urge_cooldown_minutes")).longValue() * 60));
    return out;
  }

  Map<String, Object> config() {
    return db.queryForMap(
        "SELECT urge_cooldown_minutes,auto_urge_enabled,auto_urge_hours,auto_urge_repeat_hours FROM"
            + " workflow_config WHERE id=1");
  }

  @Transactional
  Object manual(String business, long id, Actor actor) {
    var t = target(business, id, true);
    ApiException.require(t != null, "仅可催办审批中的单据");
    if (t.owner() != actor.id()) throw new ApiException(403, "仅发起人本人可催办");
    var users = recipients(t);
    ApiException.require(!users.isEmpty(), "当前没有在职审批人，请联系管理员改派");
    var now = clock.instant();
    long minutes = ((Number) config().get("urge_cooldown_minutes")).longValue();
    for (var u : users) {
      var prev = last(t, ((Number) u.get("id")).longValue(), null, false);
      if (prev != null && now.isBefore(prev.plusSeconds(minutes * 60))) {
        long wait =
            Math.max(
                1, (Duration.between(now, prev.plusSeconds(minutes * 60)).getSeconds() + 59) / 60);
        throw new ApiException(
            429,
            "已于 "
                + prev.atZone(AttendanceService.ZONE).toLocalTime().withSecond(0).withNano(0)
                + " 催办过，请 "
                + wait
                + " 分钟后再试");
      }
    }
    var ids = new ArrayList<Long>();
    var names = new ArrayList<String>();
    for (var u : users) {
      long uid = ((Number) u.get("id")).longValue();
      send(t, uid, actor.id(), "MANUAL", now, "【催办】" + actor.name() + "提醒您尽快审批：");
      ids.add(uid);
      names.add(u.get("name").toString());
    }
    auth.audit(actor.id(), "URGE_" + business, id);
    return Map.of(
        "message",
        "已催办 " + String.join("、", names),
        "urgedUserIds",
        ids,
        "nextAvailableAt",
        now.plusSeconds(minutes * 60));
  }

  void send(Target t, long user, Long actor, String mode, Instant now, String prefix) {
    db.update(
        "INSERT INTO"
            + " approval_urges(business,target_id,revision,step,approver_id,actor_id,mode,activated_at,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        t.business(),
        t.id(),
        t.revision(),
        t.step(),
        user,
        actor,
        mode,
        OffsetDateTime.ofInstant(t.activated(), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    notifications.write(
        user,
        prefix + t.title(),
        switch (t.business()) {
          case "REQUEST" -> "request_id";
          case "CONTRACT" -> "contract_id";
          default -> "asset_request_id";
        },
        t.id());
  }

  @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Shanghai")
  @Transactional
  public int auto() {
    return runAuto(clock.instant(), null);
  }

  @Transactional
  public int autoForDev(Instant now, long requestId) {
    return runAuto(now, requestId);
  }

  int runAuto(Instant now, Long onlyRequest) {
    if (!Boolean.TRUE.equals(
        db.queryForObject("SELECT pg_try_advisory_xact_lock(2026111501)", Boolean.class))) return 0;
    var c = config();
    if (!Boolean.TRUE.equals(c.get("auto_urge_enabled"))) return 0;
    long hours = ((Number) c.get("auto_urge_hours")).longValue(),
        repeat = ((Number) c.get("auto_urge_repeat_hours")).longValue();
    int count = 0;
    for (String business : List.of("REQUEST", "CONTRACT", "ASSET")) {
      if (onlyRequest != null && !business.equals("REQUEST")) continue;
      String table =
          switch (business) {
            case "REQUEST" -> "requests";
            case "CONTRACT" -> "contracts";
            default -> "asset_requests";
          };
      for (long id :
          db.queryForList(
              "SELECT id FROM "
                  + table
                  + " WHERE status='PENDING'"
                  + (onlyRequest == null ? "" : " AND id=" + onlyRequest)
                  + " ORDER BY id",
              Long.class)) {
        var t = target(business, id, true);
        if (t == null || now.isBefore(t.activated().plusSeconds(hours * 3600))) continue;
        for (var u : recipients(t)) {
          long uid = ((Number) u.get("id")).longValue();
          var prev = last(t, uid, "AUTO", true);
          if (prev != null && (repeat == 0 || now.isBefore(prev.plusSeconds(repeat * 3600))))
            continue;
          var latestAny = last(t, uid, null, true);
          long cooldownMinutes = ((Number) c.get("urge_cooldown_minutes")).longValue();
          if (latestAny != null && now.isBefore(latestAny.plusSeconds(cooldownMinutes * 60)))
            continue;
          send(t, uid, null, "AUTO", now, "【超时提醒】以下审批已等待超过 " + hours + " 小时：");
          count++;
        }
      }
    }
    return count;
  }
}
