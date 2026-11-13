package com.company.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class RequestTemplateController {
  final JdbcTemplate db;
  final Auth auth;
  final ObjectMapper json;

  RequestTemplateController(JdbcTemplate db, Auth auth, ObjectMapper json) {
    this.db = db;
    this.auth = auth;
    this.json = json;
  }

  record Input(
      @NotBlank @Size(max = 60) String name,
      @NotBlank String kind,
      @NotNull Map<String, Object> payload) {}

  static final Set<String> KINDS = Set.of("LEAVE", "TRAVEL", "OUTING", "EXPENSE");

  Map<String, Object> clean(Map<String, Object> payload, List<String> warnings) {
    var p = new LinkedHashMap<String, Object>();
    for (String key :
        List.of(
            "typeId",
            "reason",
            "items",
            "ccUserIds",
            "amount",
            "startTime",
            "endTime",
            "durationDays")) if (payload.get(key) != null) p.put(key, payload.get(key));
    ApiException.require(
        p.getOrDefault("reason", "") instanceof String
            && p.getOrDefault("reason", "").toString().length() <= 2000,
        "理由最多2000字");
    if (p.containsKey("typeId")) {
      ApiException.require(p.get("typeId") instanceof Number, "假期类型无效");
      if (db.queryForObject(
              "SELECT count(*) FROM leave_types WHERE id=? AND enabled",
              Long.class,
              ((Number) p.get("typeId")).longValue())
          == 0) {
        p.remove("typeId");
        warnings.add("已剔除停用的假期类型");
      }
    }
    if (p.containsKey("ccUserIds")) {
      ApiException.require(p.get("ccUserIds") instanceof List<?>, "抄送人无效");
      var ids = (List<?>) p.get("ccUserIds");
      ApiException.require(ids.size() <= 100, "抄送人最多100位");
      var valid = new LinkedHashSet<Long>();
      for (Object id : ids) {
        ApiException.require(id instanceof Number, "抄送人无效");
        long uid = ((Number) id).longValue();
        if (db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, uid)
            > 0) valid.add(uid);
        else warnings.add("已剔除停用的抄送人");
      }
      p.put("ccUserIds", valid);
    }
    if (p.containsKey("items")) {
      ApiException.require(p.get("items") instanceof List<?>, "报销明细无效");
      var items = (List<?>) p.get("items");
      ApiException.require(items.size() <= 100, "报销明细最多100条");
      var safe = new ArrayList<Map<String, Object>>();
      for (var item : items) {
        ApiException.require(item instanceof Map<?, ?>, "报销明细无效");
        var row = (Map<?, ?>) item;
        Object desc = row.get("description"), amount = row.get("amount");
        ApiException.require(
            desc instanceof String && desc.toString().length() <= 500 && amount instanceof Number,
            "报销明细描述或金额无效");
        safe.add(Map.of("description", desc, "amount", amount));
      }
      p.put("items", safe);
    }
    for (String key : List.of("startTime", "endTime"))
      if (p.containsKey(key))
        ApiException.require(Set.of("09:00", "13:00", "18:00").contains(p.get(key)), "半天选项无效");
    for (String key : List.of("amount", "durationDays"))
      if (p.containsKey(key))
        ApiException.require(
            p.get(key) instanceof Number && ((Number) p.get(key)).doubleValue() >= 0, "金额或时长无效");
    return p;
  }

  String encode(Object p) {
    try {
      return json.writeValueAsString(p);
    } catch (Exception e) {
      throw new ApiException(400, "模板格式无效");
    }
  }

  Map<String, Object> decoded(Map<String, Object> row) {
    try {
      row.put("payload", json.readValue(row.get("payload").toString(), Map.class));
      var warnings = new ArrayList<String>();
      row.put("payload", clean((Map<String, Object>) row.get("payload"), warnings));
      row.put("warnings", warnings);
      return row;
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @GetMapping("/request-templates")
  Object list(@RequestParam(defaultValue = "") String kind, HttpServletRequest r) {
    ApiException.require(kind.isEmpty() || KINDS.contains(kind), "模板类型无效");
    return db
        .queryForList(
            "SELECT * FROM request_templates WHERE user_id=? AND (?='' OR kind=?) ORDER BY"
                + " updated_at DESC,id DESC",
            Auth.actor(r).id(),
            kind,
            kind)
        .stream()
        .map(this::decoded)
        .toList();
  }

  @PostMapping("/request-templates")
  @Transactional
  Object create(@Valid @RequestBody Input in, HttpServletRequest r) {
    return save(null, in, Auth.actor(r));
  }

  @PutMapping("/request-templates/{id}")
  @Transactional
  Object update(@PathVariable long id, @Valid @RequestBody Input in, HttpServletRequest r) {
    return save(id, in, Auth.actor(r));
  }

  Object save(Long id, Input in, Actor a) {
    db.execute("SELECT pg_advisory_xact_lock(20261115,hashint8(" + a.id() + "))");
    ApiException.require(KINDS.contains(in.kind()), "仅请假、出差、外出和报销支持模板");
    if (id != null) owned(id, a);
    else
      ApiException.require(
          db.queryForObject(
                  "SELECT count(*) FROM request_templates WHERE user_id=?", Long.class, a.id())
              < 30,
          "每人最多30个模板");
    ApiException.require(
        db.queryForObject(
                "SELECT count(*) FROM request_templates WHERE user_id=? AND name=? AND id<>?",
                Long.class,
                a.id(),
                in.name().trim(),
                id == null ? 0 : id)
            == 0,
        "模板名称已存在");
    var warnings = new ArrayList<String>();
    String p = encode(clean(in.payload(), warnings));
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO request_templates(user_id,name,kind,payload) VALUES(?,?,?,?::jsonb)"
                  + " RETURNING id",
              Long.class,
              a.id(),
              in.name().trim(),
              in.kind(),
              p);
    else
      db.update(
          "UPDATE request_templates SET name=?,kind=?,payload=?::jsonb,updated_at=now() WHERE id=?"
              + " AND user_id=?",
          in.name().trim(),
          in.kind(),
          p,
          id,
          a.id());
    auth.audit(a.id(), "SAVE_REQUEST_TEMPLATE", id);
    return Map.of("id", id, "warnings", warnings);
  }

  void owned(long id, Actor a) {
    if (db.queryForObject(
            "SELECT count(*) FROM request_templates WHERE id=? AND user_id=?",
            Long.class,
            id,
            a.id())
        == 0) throw new ApiException(403, "仅可管理本人的模板");
  }

  @DeleteMapping("/request-templates/{id}")
  @Transactional
  Object delete(@PathVariable long id, HttpServletRequest r) {
    var a = Auth.actor(r);
    owned(id, a);
    db.update("DELETE FROM request_templates WHERE id=? AND user_id=?", id, a.id());
    auth.audit(a.id(), "DELETE_REQUEST_TEMPLATE", id);
    return Map.of("message", "模板已删除");
  }

  @GetMapping("/requests/{id}/copy")
  Object copy(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    var rows = db.queryForList("SELECT * FROM requests WHERE id=? AND user_id=?", id, a.id());
    if (rows.isEmpty()) throw new ApiException(403, "仅发起人可复制申请");
    var r = rows.getFirst();
    ApiException.require(KINDS.contains(r.get("kind")), "该类型暂不支持复制");
    var p = new LinkedHashMap<String, Object>();
    p.put("typeId", r.get("type_id"));
    p.put("reason", r.get("reason"));
    p.put("amount", r.get("amount"));
    p.put(
        "items",
        db.queryForList(
            "SELECT description,amount FROM expense_items WHERE request_id=? ORDER BY line_no",
            id));
    p.put(
        "ccUserIds",
        db.queryForList("SELECT user_id FROM request_cc WHERE request_id=?", Long.class, id));
    if (r.get("start_at") != null) {
      p.put("startTime", AttendanceService.time(r.get("start_at")).toLocalTime().toString());
      p.put("endTime", AttendanceService.time(r.get("end_at")).toLocalTime().toString());
      p.put("durationDays", r.get("days"));
    }
    var warnings = new ArrayList<String>();
    var payload = clean(p, warnings);
    warnings.add("请重新选择日期，附件不会复制");
    return Map.of("kind", r.get("kind"), "payload", payload, "warnings", warnings);
  }
}
