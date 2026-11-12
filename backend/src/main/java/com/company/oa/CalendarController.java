package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class CalendarController {
  @org.springframework.beans.factory.annotation.Autowired
  NotificationService notifications;
  final JdbcTemplate db;
  final Auth auth;

  CalendarController(JdbcTemplate db, Auth auth) {
    this.db = db;
    this.auth = auth;
  }

  // Every calendar mutation takes the same transaction-scoped lock, including room disable/delete.
  // This also serializes reservations for rooms with no existing meetings to lock.
  void lock() {
    db.queryForList("SELECT pg_advisory_xact_lock(630206)");
  }

  List<Map<String, Object>> rows(String sql, Object... params) {
    var rows = db.queryForList(sql, params);
    rows.forEach(r -> r.replaceAll((k, v) -> v instanceof Timestamp t ? t.toLocalDateTime() : v));
    return rows;
  }

  Map<String, Object> meeting(long id) {
    var list =
        rows(
            "SELECT m.*,u.name organizer_name,r.name room_name FROM meetings m JOIN users u ON"
                + " u.id=m.organizer_id LEFT JOIN meeting_rooms r ON r.id=m.room_id WHERE m.id=?",
            id);
    if (list.isEmpty()) throw new ApiException(404, "会议不存在");
    return list.getFirst();
  }

  void canEdit(Map<String, Object> m, Actor a) {
    if (!a.admin() && ((Number) m.get("organizer_id")).longValue() != a.id())
      throw new ApiException(403, "仅组织者或管理员可修改或取消会议");
    ApiException.require(m.get("cancelled_at") == null, "会议已取消，不可再修改或回复");
  }

  Set<Long> attendees(long id) {
    return new LinkedHashSet<>(
        db.queryForList(
            "SELECT user_id FROM meeting_attendees WHERE meeting_id=?", Long.class, id));
  }

  record Room(
      @NotBlank @Size(max = 100) String name,
      @Size(max = 200) String location,
      @Positive @Digits(integer = 9, fraction = 0) java.math.BigDecimal capacity,
      @NotNull Boolean enabled) {}

  @GetMapping("/meeting-rooms")
  Object rooms(
      @RequestParam(defaultValue = "false") boolean management,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    if (management) Auth.actor(req).requireAdmin();
    return rows(
        "SELECT * FROM meeting_rooms WHERE (? OR enabled) ORDER BY id LIMIT 200 OFFSET ?",
        management,
        Math.max(0, offset));
  }

  @PostMapping("/meeting-rooms")
  @Transactional
  Object createRoom(@Valid @RequestBody Room in, HttpServletRequest req) {
    return saveRoom(null, in, req);
  }

  @PutMapping("/meeting-rooms/{id}")
  @Transactional
  Object updateRoom(@PathVariable long id, @Valid @RequestBody Room in, HttpServletRequest req) {
    return saveRoom(id, in, req);
  }

  Object saveRoom(Long id, Room in, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    a.requireAdmin();
    lock();
    if (id != null && db.queryForList("SELECT id FROM meeting_rooms WHERE id=?", id).isEmpty())
      throw new ApiException(404, "会议室不存在");
    if (in.enabled()
        && !db.queryForList(
                "SELECT id FROM meeting_rooms WHERE enabled AND lower(name)=lower(?) AND id<>?",
                in.name().trim(),
                id == null ? 0 : id)
            .isEmpty()) throw new ApiException(409, "启用会议室名称不可重复");
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO meeting_rooms(name,location,capacity,enabled) VALUES(?,?,?,?) RETURNING"
                  + " id",
              Long.class,
              in.name().trim(),
              in.location(),
              in.capacity(),
              in.enabled());
    else
      db.update(
          "UPDATE meeting_rooms SET name=?,location=?,capacity=?,enabled=? WHERE id=?",
          in.name().trim(),
          in.location(),
          in.capacity(),
          in.enabled(),
          id);
    auth.audit(a.id(), in.enabled() ? "SAVE_MEETING_ROOM" : "DISABLE_MEETING_ROOM", id);
    return Map.of("id", id);
  }

  @DeleteMapping("/meeting-rooms/{id}")
  @Transactional
  Object deleteRoom(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    a.requireAdmin();
    lock();
    if (db.queryForList("SELECT id FROM meeting_rooms WHERE id=?", id).isEmpty())
      throw new ApiException(404, "会议室不存在");
    boolean history =
        db.queryForObject("SELECT count(*) FROM meetings WHERE room_id=?", Long.class, id) > 0;
    if (history) db.update("UPDATE meeting_rooms SET enabled=false WHERE id=?", id);
    else db.update("DELETE FROM meeting_rooms WHERE id=?", id);
    auth.audit(a.id(), history ? "DISABLE_MEETING_ROOM" : "DELETE_MEETING_ROOM", id);
    return Map.of("message", history ? "已有会议记录，会议室已停用" : "会议室已删除");
  }

  record Input(
      @NotBlank @Size(max = 200) String title,
      @NotNull LocalDateTime startAt,
      @NotNull LocalDateTime endAt,
      Long roomId,
      @Size(max = 2000) String description,
      @Size(max = 200) List<@NotNull Long> attendeeIds) {}

  static void validateTime(LocalDateTime start, LocalDateTime end) {
    ApiException.require(start != null && end != null && end.isAfter(start), "结束时间必须晚于开始时间（北京时间）");
  }

  @GetMapping("/meetings")
  Object list(
      @RequestParam(required = false) LocalDate day,
      @RequestParam(required = false) LocalDateTime from,
      @RequestParam(required = false) LocalDateTime to,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "false") boolean includeCancelled,
      @RequestParam(required = false) Long roomId,
      @RequestParam(required = false) Long organizerId,
      @RequestParam(defaultValue = "0") int offset,
      HttpServletRequest req) {
    if (day != null) {
      from = day.atStartOfDay();
      to = day.plusDays(1).atStartOfDay();
    }
    if (from == null) from = LocalDate.now(ZoneId.of("Asia/Shanghai")).atStartOfDay();
    if (to == null) to = from.plusDays(1);
    validateTime(from, to);
    Actor a = Auth.actor(req);
    return rows(
        "SELECT m.id,m.title,m.start_at,m.end_at,m.room_id,r.name room_name,m.organizer_id,u.name"
            + " organizer_name,m.cancelled_at FROM meetings m JOIN users u ON u.id=m.organizer_id"
            + " LEFT JOIN meeting_rooms r ON r.id=m.room_id WHERE m.start_at<? AND m.end_at>? AND"
            + " (? OR m.cancelled_at IS NULL) AND (?::bigint IS NULL OR m.room_id=?) AND (?::bigint"
            + " IS NULL OR m.organizer_id=?) AND (NOT ? OR m.organizer_id=? OR EXISTS(SELECT 1 FROM"
            + " meeting_attendees a WHERE a.meeting_id=m.id AND a.user_id=?)) ORDER BY"
            + " m.start_at,m.id LIMIT 200 OFFSET ?",
        to,
        from,
        includeCancelled,
        roomId,
        roomId,
        organizerId,
        organizerId,
        mine,
        a.id(),
        a.id(),
        Math.max(0, offset));
  }

  @GetMapping("/meetings/{id}")
  Object detail(@PathVariable long id, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    var m = meeting(id);
    boolean organizer = ((Number) m.get("organizer_id")).longValue() == a.id();
    boolean participant = attendees(id).contains(a.id());
    boolean full = a.admin() || organizer || participant;
    m.put("canEdit", a.admin() || organizer);
    m.put("canReply", participant && !organizer && m.get("cancelled_at") == null);
    if (!full) m.remove("description");
    if (full) {
      var people =
          rows(
              "SELECT a.*,u.name,u.employee_no FROM meeting_attendees a JOIN users u ON"
                  + " u.id=a.user_id WHERE a.meeting_id=? ORDER BY u.employee_no",
              id);
      if (!a.admin() && !organizer)
        people.forEach(
            p -> {
              if (((Number) p.get("user_id")).longValue() != a.id()) {
                p.remove("response");
                p.remove("reason");
                p.remove("responded_at");
              }
            });
      m.put("attendees", people);
    }
    return m;
  }

  @PostMapping("/meetings")
  @Transactional
  Object create(@Valid @RequestBody Input in, HttpServletRequest req) {
    return save(null, in, Auth.actor(req));
  }

  @PutMapping("/meetings/{id}")
  @Transactional
  Object update(@PathVariable long id, @Valid @RequestBody Input in, HttpServletRequest req) {
    return save(id, in, Auth.actor(req));
  }

  Object save(Long id, Input in, Actor a) {
    lock();
    validateTime(in.startAt(), in.endAt());
    var old = id == null ? null : meeting(id);
    if (old != null) canEdit(old, a);
    long organizer = old == null ? a.id() : ((Number) old.get("organizer_id")).longValue();
    Set<Long> invited =
        new LinkedHashSet<>(in.attendeeIds() == null ? List.of() : in.attendeeIds());
    invited.remove(organizer);
    for (long person : invited)
      ApiException.require(
          db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled", Long.class, person)
              == 1,
          "参会人必须为在职员工");
    List<String> warnings = new ArrayList<>();
    boolean moved =
        old != null
            && (!Objects.equals(old.get("start_at"), in.startAt())
                || !Objects.equals(old.get("end_at"), in.endAt())
                || !Objects.equals(old.get("room_id"), in.roomId()));
    if (in.roomId() != null) {
      var room = rows("SELECT * FROM meeting_rooms WHERE id=?", in.roomId());
      ApiException.require(!room.isEmpty(), "会议室不存在");
      // An unchanged historical reservation remains editable after the room is disabled.
      ApiException.require(
          Boolean.TRUE.equals(room.getFirst().get("enabled")) || (old != null && !moved),
          "会议室已停用，不可新订或改期到该会议室");
      var conflicts =
          rows(
              "SELECT id,title FROM meetings WHERE room_id=? AND cancelled_at IS NULL AND"
                  + " start_at<? AND end_at>? AND id<>? ORDER BY start_at LIMIT 1",
              in.roomId(),
              in.endAt(),
              in.startAt(),
              id == null ? 0 : id);
      if (!conflicts.isEmpty())
        throw new ApiException(
            409,
            "会议室时段冲突："
                + conflicts.getFirst().get("title")
                + "（会议 #"
                + conflicts.getFirst().get("id")
                + "）");
      Object capacity = room.getFirst().get("capacity");
      if (capacity != null && invited.size() + 1 > ((Number) capacity).intValue())
        warnings.add("参会人数超过会议室容纳人数");
    }
    var all = new LinkedHashSet<>(invited);
    all.add(organizer);
    for (long person : all) {
      if (db.queryForObject(
              "SELECT count(*) FROM meetings m WHERE m.cancelled_at IS NULL AND m.id<>? AND"
                  + " m.start_at<? AND m.end_at>? AND (m.organizer_id=? OR EXISTS(SELECT 1 FROM"
                  + " meeting_attendees a WHERE a.meeting_id=m.id AND a.user_id=?))",
              Long.class,
              id == null ? 0 : id,
              in.endAt(),
              in.startAt(),
              person,
              person)
          > 0) warnings.add("员工 #" + person + " 存在个人日程冲突，请协调");
    }
    Set<Long> previous = id == null ? Set.of() : attendees(id);
    if (id == null)
      id =
          db.queryForObject(
              "INSERT INTO meetings(organizer_id,title,start_at,end_at,room_id,description)"
                  + " VALUES(?,?,?,?,?,?) RETURNING id",
              Long.class,
              organizer,
              in.title().trim(),
              in.startAt(),
              in.endAt(),
              in.roomId(),
              in.description());
    else
      db.update(
          "UPDATE meetings SET"
              + " title=?,start_at=?,end_at=?,room_id=?,description=?,updated_at=localtimestamp"
              + " WHERE id=?",
          in.title().trim(),
          in.startAt(),
          in.endAt(),
          in.roomId(),
          in.description(),
          id);
    for (long person : previous)
      if (!invited.contains(person)) {
        db.update("DELETE FROM meeting_attendees WHERE meeting_id=? AND user_id=?", id, person);
        notify(person, id, "你已被移出会议：" + in.title());
      }
    for (long person : invited) {
      if (!previous.contains(person)) {
        db.update("INSERT INTO meeting_attendees(meeting_id,user_id) VALUES(?,?)", id, person);
        notify(person, id, "会议邀请：" + in.title());
      } else if (moved) {
        db.update(
            "UPDATE meeting_attendees SET response='PENDING',reason=NULL,responded_at=NULL WHERE"
                + " meeting_id=? AND user_id=?",
            id,
            person);
        notify(person, id, "会议时间或会议室已变更，请重新回复：" + in.title());
      }
    }
    auth.audit(a.id(), old == null ? "CREATE_MEETING" : "UPDATE_MEETING", id);
    return Map.of("id", id, "warnings", warnings);
  }

  void notify(long user, long meeting, String message) {
    notifications.write(user,
        message, "meeting_id",
        meeting);
  }

  @PostMapping("/meetings/{id}/cancel")
  @Transactional
  Object cancel(@PathVariable long id, HttpServletRequest req) {
    lock();
    Actor a = Auth.actor(req);
    var m = meeting(id);
    canEdit(m, a);
    db.update(
        "UPDATE meetings SET cancelled_at=localtimestamp,updated_at=localtimestamp WHERE id=?", id);
    for (long person : attendees(id)) notify(person, id, "会议已取消：" + m.get("title"));
    auth.audit(a.id(), "CANCEL_MEETING", id);
    return Map.of("message", "会议已取消，会议室已释放");
  }

  record Reply(@NotNull String response, @Size(max = 300) String reason) {}

  @PostMapping("/meetings/{id}/reply")
  @Transactional
  Object reply(@PathVariable long id, @Valid @RequestBody Reply in, HttpServletRequest req) {
    lock();
    Actor a = Auth.actor(req);
    var m = meeting(id);
    if (!attendees(id).contains(a.id())) throw new ApiException(403, "仅受邀参会人可回复，组织者无需回复自己");
    ApiException.require(m.get("cancelled_at") == null, "会议已取消，不可再回复");
    ApiException.require(Set.of("ACCEPTED", "DECLINED").contains(in.response()), "请选择接受或请假");
    db.update(
        "UPDATE meeting_attendees SET response=?,reason=?,responded_at=localtimestamp WHERE"
            + " meeting_id=? AND user_id=?",
        in.response(),
        in.response().equals("DECLINED") ? in.reason() : null,
        id,
        a.id());
    auth.audit(a.id(), "REPLY_MEETING", id);
    return Map.of("message", "参会回复已保存");
  }
}
