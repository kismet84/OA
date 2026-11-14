package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
class AssetController {
  @org.springframework.beans.factory.annotation.Autowired ApprovalUrgeService urges;
  @org.springframework.beans.factory.annotation.Autowired
  NotificationService notifications;
  final JdbcTemplate db;
  final Auth auth;

  AssetController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  static boolean manages(Actor a) {
    return a.admin() || a.has("FINANCE");
  }

  static void manage(Actor a) {
    if (!manages(a)) throw new ApiException(403, "仅管理员或财务可维护和发放资产");
  }

  List<Map<String, Object>> rows(String sql, Object... args) {
    var r = db.queryForList(sql, args);
    r.forEach(x -> x.replaceAll((k, v) -> v instanceof Timestamp t ? t.toLocalDateTime() : v));
    return r;
  }

  Map<String, Object> one(String sql, Object... args) {
    var r = rows(sql, args);
    if (r.isEmpty()) throw new ApiException(404, "资产或记录不存在");
    return r.getFirst();
  }

  long number(Map<String, Object> r, String key) {
    return ((Number) r.get(key)).longValue();
  }

  void conflict(boolean ok, String text) {
    if (!ok) throw new ApiException(409, text);
  }

  Map<String, Object> category(long id) {
    return one("SELECT * FROM asset_categories WHERE id=? FOR UPDATE", id);
  }

  void audit(Actor a, String action, long id, String reason) {
    String target = id + ":" + (reason == null ? "" : reason);
    // Existing audit targets are limited to 200 characters; full notes stay on the request.
    auth.audit(a.id(), action, target.substring(0, Math.min(target.length(), 200)));
  }

  void notifyApplicant(Map<String, Object> r, String text) {
    notifications.write(r.get("user_id"),
        "资产领用：" + text.substring(0, Math.min(text.length(), 480)), "asset_request_id",
        r.get("id"));
  }

  @GetMapping("/categories")
  Object categories(HttpServletRequest req) {
    manage(Auth.actor(req));
    return rows("SELECT * FROM asset_categories ORDER BY id");
  }

  record Category(@NotBlank @Size(max = 100) String name, @NotNull String kind, boolean enabled) {}

  @PostMapping("/categories")
  @Transactional
  Object createCategory(@Valid @RequestBody Category in, HttpServletRequest req) {
    var a = Auth.actor(req);
    manage(a);
    ApiException.require(Set.of("ITEM", "CONSUMABLE").contains(in.kind()), "管理方式无效");
    long id =
        db.queryForObject(
            "INSERT INTO asset_categories(name,kind,enabled) VALUES(?,?,?) RETURNING id",
            Long.class,
            in.name().trim(),
            in.kind(),
            in.enabled());
    audit(a, "CREATE_ASSET_CATEGORY", id, null);
    return Map.of("id", id);
  }

  @PutMapping("/categories/{id}")
  @Transactional
  Object editCategory(
      @PathVariable long id, @Valid @RequestBody Category in, HttpServletRequest req) {
    var a = Auth.actor(req);
    manage(a);
    var c = category(id);
    ApiException.require(c.get("kind").equals(in.kind()), "分类管理方式不可修改，请新建分类");
    db.update(
        "UPDATE asset_categories SET name=?,enabled=? WHERE id=?",
        in.name().trim(),
        in.enabled(),
        id);
    audit(a, "UPDATE_ASSET_CATEGORY", id, null);
    return Map.of("id", id);
  }

  @GetMapping("/catalog")
  Object catalog(HttpServletRequest req) {
    Auth.actor(req);
    return rows(
        "SELECT c.id,c.name,c.kind,NULL::varchar unit,(SELECT count(*) FROM assets a WHERE"
            + " a.category_id=c.id AND a.enabled AND a.status='AVAILABLE') available FROM"
            + " asset_categories c WHERE c.enabled AND c.kind='ITEM' UNION ALL SELECT"
            + " a.id,a.name,c.kind,a.unit,a.stock::bigint available FROM assets a JOIN"
            + " asset_categories c ON c.id=a.category_id WHERE c.enabled AND a.enabled AND"
            + " c.kind='CONSUMABLE' AND a.status='AVAILABLE' ORDER BY kind,id");
  }

  @GetMapping
  Object ledger(
      @RequestParam(required = false) Long categoryId,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    manage(Auth.actor(req));
    ApiException.require(offset >= 0, "分页参数无效");
    ApiException.require(
        status == null || Set.of("AVAILABLE", "ASSIGNED", "REPAIR", "SCRAPPED").contains(status),
        "资产状态无效");
    return rows(
        "SELECT a.*,c.name category_name,c.kind FROM assets a JOIN asset_categories c ON"
            + " c.id=a.category_id WHERE (?::bigint IS NULL OR a.category_id=?) AND (?::text IS"
            + " NULL OR a.status=?) ORDER BY a.id DESC LIMIT 200 OFFSET ?",
        categoryId,
        categoryId,
        status,
        status,
        Math.max(0, offset));
  }

  record Asset(
      @NotNull Long categoryId,
      @Size(max = 100) String code,
      @NotBlank @Size(max = 100) String name,
      @Size(max = 200) String brand,
      @Size(max = 200) String serialNumber,
      LocalDate purchaseDate,
      @Size(max = 200) String location,
      @Size(max = 2000) String note,
      UUID attachmentId,
      @Size(max = 30) String unit,
      boolean enabled) {}

  @PostMapping
  @Transactional
  Object create(@Valid @RequestBody Asset in, HttpServletRequest req) {
    return save(null, in, Auth.actor(req));
  }

  @PutMapping("/{id}")
  @Transactional
  Object edit(@PathVariable long id, @Valid @RequestBody Asset in, HttpServletRequest req) {
    return save(id, in, Auth.actor(req));
  }

  Object save(Long id, Asset in, Actor a) {
    manage(a);
    var c = category(in.categoryId());
    ApiException.require(Boolean.TRUE.equals(c.get("enabled")), "分类已停用");
    boolean item = c.get("kind").equals("ITEM");
    ApiException.require(
        item
            ? in.code() != null && !in.code().isBlank()
            : in.unit() != null && !in.unit().isBlank(),
        "按件资产需填写编号，消耗品需填写单位");
    if (in.attachmentId() != null)
      AttachmentClaims.check(db, in.attachmentId(), a.id(), "ASSET", id);
    if (id != null) {
      var old = one("SELECT * FROM assets WHERE id=? FOR UPDATE", id);
      ApiException.require(number(old, "category_id") == in.categoryId(), "资产分类不可修改");
    }
    String code = item ? in.code().trim() : null;
    conflict(
        code == null
            || db.queryForObject(
                    "SELECT count(*) FROM assets WHERE code=? AND (?::bigint IS NULL OR id<>?)",
                    Long.class,
                    code,
                    id,
                    id)
                == 0,
        "资产编号重复");
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO"
                  + " assets(category_id,code,name,brand,serial_number,purchase_date,location,note,attachment_id,unit,enabled)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
              Long.class,
              in.categoryId(),
              code,
              in.name().trim(),
              in.brand(),
              in.serialNumber(),
              in.purchaseDate(),
              in.location(),
              in.note(),
              in.attachmentId(),
              in.unit(),
              in.enabled());
    else
      db.update(
          "UPDATE assets SET"
              + " code=?,name=?,brand=?,serial_number=?,purchase_date=?,location=?,note=?,attachment_id=?,unit=?,enabled=?"
              + " WHERE id=?",
          code,
          in.name().trim(),
          in.brand(),
          in.serialNumber(),
          in.purchaseDate(),
          in.location(),
          in.note(),
          in.attachmentId(),
          in.unit(),
          in.enabled(),
          id);
    audit(a, "SAVE_ASSET", id, null);
    return Map.of("id", id);
  }

  record Adjustment(Integer delta, String status, @NotBlank @Size(max = 120) String reason) {}

  @PostMapping("/{id}/adjust")
  @Transactional
  Object adjust(@PathVariable long id, @Valid @RequestBody Adjustment in, HttpServletRequest req) {
    var a = Auth.actor(req);
    manage(a);
    var r =
        one(
            "SELECT a.*,c.kind FROM assets a JOIN asset_categories c ON c.id=a.category_id WHERE"
                + " a.id=? FOR UPDATE OF a",
            id);
    if (r.get("kind").equals("ITEM")) {
      ApiException.require(
          in.delta() == null
              && in.status() != null
              && Set.of("AVAILABLE", "REPAIR", "SCRAPPED").contains(in.status()),
          "请选择在库、维修中或已报废");
      conflict(!r.get("status").equals("ASSIGNED"), "已领用资产须先登记归还，不能直接调整状态或报废");
      db.update("UPDATE assets SET status=? WHERE id=?", in.status(), id);
    } else {
      ApiException.require(
          in.status() == null
              && in.delta() != null
              && in.delta() != 0
              && Math.abs((long) in.delta()) <= 1000000,
          "请填写有效库存调整数量");
      conflict(
          db.update(
                  "UPDATE assets SET stock=stock+? WHERE id=? AND stock::bigint+? BETWEEN 0 AND"
                      + " 100000000",
                  in.delta(),
                  id,
                  in.delta())
              == 1,
          "库存不足或数量超过上限");
    }
    audit(a, "ADJUST_ASSET", id, "变动=" + in.delta() + ",状态=" + in.status() + "," + in.reason());
    return Map.of("message", "调整已登记");
  }

  record Application(
      Long categoryId,
      Long consumableId,
      @Min(1) @Max(100000) int quantity,
      @NotBlank @Size(max = 2000) String purpose,
      @NotNull LocalDate expectedDate,
      Boolean needsReturn,
      LocalDate returnDate) {}

  @PostMapping("/requests")
  @Transactional
  Object apply(@Valid @RequestBody Application in, HttpServletRequest req) {
    var a = Auth.actor(req);
    ApprovalSupport.organizationSnapshot(db);
    ApiException.require((in.categoryId() == null) != (in.consumableId() == null), "请选择按件分类或具体消耗品");
    long cid;
    if (in.consumableId() != null) {
      var asset = one("SELECT * FROM assets WHERE id=?", in.consumableId());
      cid = number(asset, "category_id");
      ApiException.require(
          Boolean.TRUE.equals(asset.get("enabled")) && asset.get("status").equals("AVAILABLE"),
          "消耗品已停用");
    } else cid = in.categoryId();
    var c = category(cid);
    boolean item = c.get("kind").equals("ITEM");
    ApiException.require(
        Boolean.TRUE.equals(c.get("enabled")) && item == (in.categoryId() != null),
        "分类不可申请或管理方式不匹配");
    boolean needs = item && !Boolean.FALSE.equals(in.needsReturn());
    ApiException.require(
        !needs || in.returnDate() == null || !in.returnDate().isBefore(in.expectedDate()),
        "预计归还日期不能早于领用日期");
    var u = one("SELECT department_id,manager_id FROM users WHERE id=?", a.id());
    Object manager = u.get("manager_id");
    if (manager != null
        && db.queryForObject(
                "SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, manager)
            == 0) manager = null;
    long id =
        db.queryForObject(
            "INSERT INTO"
                + " asset_requests(user_id,department_id,category_id,consumable_id,quantity,purpose,expected_date,needs_return,return_date,approver_id,status)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
            Long.class,
            a.id(),
            u.get("department_id"),
            cid,
            in.consumableId(),
            in.quantity(),
            in.purpose().trim(),
            in.expectedDate(),
            needs,
            needs ? in.returnDate() : null,
            manager,
            manager == null ? "READY" : "PENDING");
    if (manager != null) notifications.write(manager,"资产领用 #"+id+" 待您审批","asset_request_id",id);
    audit(a, "APPLY_ASSET", id, null);
    return Map.of("id", id);
  }

  String pendingSql() {
    return "(r.status='PENDING' AND r.approver_id=? OR ? AND (r.status='READY' OR"
        + " r.return_requested))";
  }

  long pending(Actor a) {
    return db.queryForObject(
        "SELECT count(*) FROM asset_requests r WHERE " + pendingSql(),
        Long.class,
        a.id(),
        manages(a));
  }

  @GetMapping("/requests")
  Object requests(
      @RequestParam(defaultValue = "mine") String scope,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) Long departmentId,
      @RequestParam(required = false) Long assetId,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    var a = Auth.actor(req);
    ApiException.require(offset >= 0, "分页参数无效");
    ApiException.require(Set.of("mine", "pending", "all").contains(scope), "查询范围无效");
    if (scope.equals("all")) manage(a);
    var args = new ArrayList<Object>();
    String where = "true";
    if (scope.equals("mine")) {
      where = "r.user_id=?";
      args.add(a.id());
    }
    if (scope.equals("pending")) {
      where = pendingSql();
      args.add(a.id());
      args.add(manages(a));
    }
    for (var f : List.of("user_id", "department_id", "asset_id")) {
      Long value =
          f.equals("user_id") ? userId : f.equals("department_id") ? departmentId : assetId;
      if (value != null) {
        where +=
            f.equals("asset_id")
                ? " AND (r.consumable_id=? OR EXISTS(SELECT 1 FROM asset_assignments x WHERE"
                    + " x.request_id=r.id AND x.asset_id=?))"
                : " AND r." + f + "=?";
        args.add(value);
        if (f.equals("asset_id")) args.add(value);
      }
    }
    args.add(Math.max(0, offset));
    return rows(
        "SELECT r.*,u.name user_name,d.name department_name,c.name category_name,c.kind,a.name"
            + " consumable_name FROM asset_requests r JOIN users u ON u.id=r.user_id JOIN"
            + " departments d ON d.id=r.department_id JOIN asset_categories c ON c.id=r.category_id"
            + " LEFT JOIN assets a ON a.id=r.consumable_id WHERE "
            + where
            + " ORDER BY r.id DESC LIMIT 200 OFFSET ?",
        args.toArray());
  }

  Map<String, Object> request(long id, Actor a) {
    var r =
        one(
            "SELECT r.*,c.name category_name,c.kind,u.name user_name FROM asset_requests r JOIN"
                + " asset_categories c ON c.id=r.category_id JOIN users u ON u.id=r.user_id WHERE"
                + " r.id=? FOR UPDATE OF r",
            id);
    if (!manages(a)
        && number(r, "user_id") != a.id()
        && !Objects.equals(r.get("approver_id"), a.id())
        && !Objects.equals(r.get("approval_actor_id"), a.id())
        && db.queryForObject("SELECT count(*) FROM approval_reassignments WHERE business='ASSET' AND target_id=? AND (previous_approver_id=? OR approver_id=?)",Long.class,id,a.id(),a.id())==0)
      throw new ApiException(403, "无权查看或处理该领用申请");
    return r;
  }

  @GetMapping("/requests/{id}")
  @Transactional
  Object detail(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    var r = request(id, a);
    r.put(
        "assignments",
        rows(
            "SELECT x.*,a.code,a.name,a.status asset_status FROM asset_assignments x JOIN assets a"
                + " ON a.id=x.asset_id WHERE x.request_id=? ORDER BY a.id",
            id));
    r.put(
        "canApprove",
        r.get("status").equals("PENDING") && Objects.equals(r.get("approver_id"), a.id()));
    r.put("reassignments", db.queryForList("SELECT h.*,u.name previous_approver_name,v.name approver_name,a.name actor_name FROM approval_reassignments h LEFT JOIN users u ON u.id=h.previous_approver_id JOIN users v ON v.id=h.approver_id JOIN users a ON a.id=h.actor_id WHERE business='ASSET' AND target_id=? ORDER BY h.id",id));
    if(r.get("approval_actor_id")!=null) r.put("approval_actor_name",db.queryForObject("SELECT name FROM users WHERE id=?",String.class,r.get("approval_actor_id")));
    r.putAll(urges.metadata("ASSET",id,a));
    r.put("canManage", manages(a));
    r.put("isOwner", number(r, "user_id") == a.id());
    return r;
  }

  record Decision(boolean approved, @Size(max = 1000) String note) {}

  @PostMapping("/requests/{id}/approve")
  @Transactional
  Object approve(@PathVariable long id, @Valid @RequestBody Decision in, HttpServletRequest req) {
    return decide(id,in,Auth.actor(req),false);
  }
  @PostMapping("/requests/{id}/proxy-approve") @Transactional
  Object proxyApprove(@PathVariable long id,@Valid @RequestBody Decision in,HttpServletRequest req) {
    var a=Auth.actor(req);a.requireAdmin();return decide(id,in,a,true);
  }
  Object decide(long id,Decision in,Actor a,boolean proxy) {
    var r = request(id, a);
    ApiException.require(number(r,"user_id")!=a.id(),"不能审批自己的领用申请");
    if (!proxy && !Objects.equals(r.get("approver_id"), a.id())) throw new ApiException(403, "仅指定直属上级可审批");
    conflict(r.get("status").equals("PENDING"), "申请不在待审批状态");
    ApiException.require(in.approved() || in.note() != null && !in.note().isBlank(), "驳回须填写原因");
    db.update(
        "UPDATE asset_requests SET status=?,approval_note=?,approval_actor_id=?,approval_acted_at=now() WHERE id=?",
        in.approved() ? "READY" : "REJECTED",
        proxy?ApprovalSupport.proxyNote(in.note()):in.note(), a.id(),
        id);
    notifyApplicant(r, in.approved() ? "审批通过，待发放" : "申请已驳回：" + in.note());
    audit(a, "APPROVE_ASSET", id, "通过=" + in.approved() + "," + in.note());
    return Map.of("message", "审批已处理");
  }

  @PostMapping("/requests/{id}/reassign") @Transactional
  Object reassign(@PathVariable long id,@Valid @RequestBody ApprovalSupport.Reassign in,HttpServletRequest req) {
    var a=Auth.actor(req);a.requireAdmin();ApprovalSupport.organizationSnapshot(db);
    var r=request(id,a);conflict(r.get("status").equals("PENDING"),"仅待审批申请可改派");
    ApprovalSupport.reassign(db,a,"ASSET",id,0,1,(Long)r.get("approver_id"),number(r,"user_id"),in);
    db.update("UPDATE asset_requests SET approver_id=? WHERE id=?",in.approverId(),id);
    notifications.write(in.approverId(),"资产领用 #"+id+" 已改派给您审批："+in.note(),"asset_request_id",id);
    audit(a,"REASSIGN_ASSET",id,in.note());return Map.of("message","审批人已改派");
  }
  @PostMapping("/requests/{id}/withdraw")
  @Transactional
  Object withdraw(@PathVariable long id, HttpServletRequest req) {
    var a = Auth.actor(req);
    var r = request(id, a);
    if (number(r, "user_id") != a.id()) throw new ApiException(403, "仅申请人可撤回");
    conflict(Set.of("PENDING", "READY").contains(r.get("status")), "仅发放前可撤回");
    db.update("UPDATE asset_requests SET status='WITHDRAWN' WHERE id=?", id);
    audit(a, "WITHDRAW_ASSET", id, null);
    return Map.of("message", "已撤回");
  }

  record Issue(List<@NotNull Long> assetIds, boolean rejected, @Size(max = 1000) String note) {}

  @PostMapping("/requests/{id}/issue")
  @Transactional
  Object issue(@PathVariable long id, @Valid @RequestBody Issue in, HttpServletRequest req) {
    var a = Auth.actor(req);
    manage(a);
    var r = request(id, a);
    conflict(r.get("status").equals("READY"), "仅待发放申请可处理");
    if (in.rejected()) {
      ApiException.require(in.note() != null && !in.note().isBlank(), "拒绝发放须填写原因");
      db.update(
          "UPDATE asset_requests SET status='REJECTED',issue_note=? WHERE id=?", in.note(), id);
      notifyApplicant(r, "拒绝发放：" + in.note());
    } else {
      var c = category(number(r, "category_id"));
      conflict(Boolean.TRUE.equals(c.get("enabled")), "分类已停用，不能发放");
      if (r.get("kind").equals("ITEM")) {
        var ids = new TreeSet<Long>(in.assetIds() == null ? List.of() : in.assetIds());
        ApiException.require(
            ids.size() == number(r, "quantity")
                && in.assetIds() != null
                && in.assetIds().size() == ids.size(),
            "选择的资产件数须与申请一致且不能重复");
        for (long asset : ids) {
          var x = one("SELECT * FROM assets WHERE id=? FOR UPDATE", asset);
          conflict(
              number(x, "category_id") == number(r, "category_id")
                  && Boolean.TRUE.equals(x.get("enabled"))
                  && x.get("status").equals("AVAILABLE"),
              "资产不在库、已停用或分类不符，不能重复发放");
          db.update("UPDATE assets SET status='ASSIGNED' WHERE id=?", asset);
          db.update(
              "INSERT INTO asset_assignments(request_id,asset_id,user_id) VALUES(?,?,?)",
              id,
              asset,
              r.get("user_id"));
        }
      } else {
        conflict(
            db.update(
                    "UPDATE assets SET stock=stock-? WHERE id=? AND enabled AND status='AVAILABLE'"
                        + " AND stock>=?",
                    r.get("quantity"),
                    r.get("consumable_id"),
                    r.get("quantity"))
                == 1,
            "库存不足或消耗品已停用");
      }
      db.update(
          "UPDATE asset_requests SET status=?,issued_at=localtimestamp,issue_note=? WHERE id=?",
          Boolean.TRUE.equals(r.get("needs_return")) ? "IN_USE" : "ISSUED",
          in.note(),
          id);
      notifyApplicant(r, "资产已发放");
    }
    audit(a, "ISSUE_ASSET", id, "拒绝=" + in.rejected() + ",资产=" + in.assetIds() + "," + in.note());
    return Map.of("message", "发放已处理");
  }

  record Return(@Size(max = 1000) String note, boolean repair) {}

  @PostMapping("/requests/{id}/return")
  @Transactional
  Object requestReturn(
      @PathVariable long id, @Valid @RequestBody Return in, HttpServletRequest req) {
    var a = Auth.actor(req);
    var r = request(id, a);
    if (number(r, "user_id") != a.id()) throw new ApiException(403, "仅领用人可发起归还登记");
    returnable(r);
    conflict(!Boolean.TRUE.equals(r.get("return_requested")), "已发起归还，请等待行政确认");
    db.update(
        "UPDATE asset_requests SET return_requested=true,return_note=? WHERE id=?", in.note(), id);
    audit(a, "REQUEST_ASSET_RETURN", id, null);
    return Map.of("message", "归还已登记，等待行政确认");
  }

  void returnable(Map<String, Object> r) {
    conflict(
        r.get("kind").equals("ITEM") && Set.of("IN_USE", "ISSUED").contains(r.get("status")),
        "仅已领用的按件资产可归还");
  }

  @PostMapping("/requests/{id}/confirm-return")
  @Transactional
  Object confirmReturn(
      @PathVariable long id, @Valid @RequestBody Return in, HttpServletRequest req) {
    var a = Auth.actor(req);
    manage(a);
    var r = request(id, a);
    returnable(r);
    ApiException.require(in.note() != null && !in.note().isBlank(), "请填写收回说明");
    for (var x :
        rows(
            "SELECT asset_id FROM asset_assignments WHERE request_id=? AND returned_at IS NULL"
                + " ORDER BY asset_id",
            id)) {
      one("SELECT id FROM assets WHERE id=? FOR UPDATE", x.get("asset_id"));
      db.update(
          "UPDATE assets SET status=? WHERE id=?",
          in.repair() ? "REPAIR" : "AVAILABLE",
          x.get("asset_id"));
    }
    db.update(
        "UPDATE asset_assignments SET returned_at=localtimestamp WHERE request_id=? AND returned_at"
            + " IS NULL",
        id);
    db.update(
        "UPDATE asset_requests SET"
            + " status='RETURNED',return_requested=false,return_note=?,returned_at=localtimestamp"
            + " WHERE id=?",
        in.note(),
        id);
    notifyApplicant(r, "归还已确认" + (in.repair() ? "，已登记维修" : "，资产已回到在库"));
    audit(a, "CONFIRM_ASSET_RETURN", id, "维修=" + in.repair() + "," + in.note());
    return Map.of("message", "已确认收回");
  }

  @GetMapping("/mine")
  Object mine(@RequestParam(defaultValue = "0") int offset, HttpServletRequest req) {
    ApiException.require(offset >= 0, "分页参数无效");
    return rows(
        "SELECT x.*,a.code,a.name,a.status asset_status,c.name category_name,r.needs_return FROM"
            + " asset_assignments x JOIN assets a ON a.id=x.asset_id JOIN asset_categories c ON"
            + " c.id=a.category_id JOIN asset_requests r ON r.id=x.request_id WHERE x.user_id=?"
            + " ORDER BY x.issued_at DESC,x.asset_id LIMIT 200 OFFSET ?",
        Auth.actor(req).id(),
        Math.max(0, offset));
  }

  @GetMapping("/outstanding/{userId}")
  Object outstanding(@PathVariable long userId, HttpServletRequest req) {
    manage(Auth.actor(req));
    return Map.of(
        "count",
        db.queryForObject(
            "SELECT count(*) FROM asset_assignments WHERE user_id=? AND returned_at IS NULL",
            Long.class,
            userId));
  }
}
