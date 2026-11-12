package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import java.util.function.IntSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

/** Small visitor ledger. No approvals, access-control hardware or external push. */
@RestController
@RequestMapping("/api/visitors")
class VisitorController {
  final JdbcTemplate db; final Auth auth; final Clock clock;
  final IntSupplier digits = new java.security.SecureRandom()::nextInt;
  static final Set<String> STATES=Set.of("WAITING","ARRIVED","LEFT","CANCELLED","EXPIRED");
  static final Map<String,String> LABELS=Map.of("WAITING","待到访","ARRIVED","已到访","LEFT","已离开","CANCELLED","已取消","EXPIRED","已过期");
  VisitorController(JdbcTemplate db,Auth auth,Clock clock){this.db=db;this.auth=auth;this.clock=clock;}
  LocalDateTime now(){return LocalDateTime.now(clock.withZone(ZoneId.of("Asia/Shanghai")));}
  String effective(){return "CASE WHEN v.status='WAITING' AND v.end_at<? THEN 'EXPIRED' ELSE v.status END";}
  String visible(Actor a){return a.admin()?"true":"(v.host_id="+a.id()+" OR EXISTS(SELECT 1 FROM users h WHERE h.id=v.host_id AND h.manager_id="+a.id()+"))";}
  void hostAllowed(Actor a,Long host){if(host!=null&&!a.admin()&&host!=a.id()&&db.queryForObject("SELECT count(*) FROM users WHERE id=? AND manager_id=?",Long.class,host,a.id())==0)throw new ApiException(403,"无权查询该接待人的访客");}
  // One short transaction lock serializes code allocation and transitions across application instances.
  void lock(){db.queryForList("SELECT pg_advisory_xact_lock(110011)");}
  static String mask(String phone){return phone.substring(0,3)+"****"+phone.substring(phone.length()-4);}
  List<Map<String,Object>> rows(String sql,Object...args){var rs=db.queryForList(sql,args);rs.forEach(r->r.replaceAll((k,v)->v instanceof java.sql.Timestamp t?t.toLocalDateTime():v));return rs;}
  Map<String,Object> get(long id,Actor a){
    var rs=rows("SELECT v.*,h.name host_name FROM visitors v JOIN users h ON h.id=v.host_id WHERE v.id=?",id);
    if(rs.isEmpty())throw new ApiException(404,"访客记录不存在");
    if(db.queryForObject("SELECT count(*) FROM visitors v WHERE v.id=? AND "+visible(a),Long.class,id)==0)throw new ApiException(403,"无权查看或处理该访客记录");
    var r=rs.getFirst();r.remove("active_code");
    if(r.get("status").equals("WAITING")&&((LocalDateTime)r.get("end_at")).isBefore(now()))r.put("status","EXPIRED");
    if(!a.admin()&&((Number)r.get("host_id")).longValue()!=a.id())r.put("phone",mask((String)r.get("phone")));
    r.put("isOwner",((Number)r.get("host_id")).longValue()==a.id());return r;
  }
  record Input(@NotBlank @Size(max=100) String name,@NotBlank @Pattern(regexp="[0-9]{7,20}") String phone,
    @NotBlank @Size(max=200) String company,@NotBlank @Size(max=1000) String purpose,
    @NotNull @Min(1) @Max(100) Integer people,@Size(max=1000) String companions,
    @NotNull LocalDateTime startAt,@NotNull LocalDateTime endAt,@Size(max=30) String plate,@Size(max=2000) String note,Long hostId){}
  String allocate(LocalDateTime at,IntSupplier random){
    // Expiration is derived on reads; only release the allocation slot, retaining the ledger status.
    db.update("UPDATE visitors SET active_code=NULL WHERE status='WAITING' AND end_at<? AND active_code IS NOT NULL",at);
    for(int n=0;n<100;n++){
      String code=String.format(Locale.ROOT,"%06d",Math.floorMod(random.getAsInt(),1000000));
      if(db.queryForObject("SELECT count(*) FROM visitors WHERE active_code=?",Long.class,code)==0)return code;
    }
    throw new ApiException(409,"有效访客码冲突，请稍后重试");
  }
  @PostMapping @Transactional Object create(@Valid @RequestBody Input in,HttpServletRequest req){return save(in,Auth.actor(req),false);}
  @PostMapping("/temporary") @Transactional Object temporary(@Valid @RequestBody Input in,HttpServletRequest req){var a=Auth.actor(req);a.requireAdmin();ApiException.require(in.hostId()!=null,"请选择在职接待人");return save(in,a,true);}
  Object save(Input in,Actor a,boolean temporary){
    long host=in.hostId()==null?a.id():in.hostId();
    if(!temporary&&host!=a.id())throw new ApiException(403,"仅可预约本人接待的访客");
    ApiException.require(in.endAt().isAfter(in.startAt()),"预计结束时间必须晚于开始时间");
    LocalDateTime at=now();ApiException.require(in.endAt().isAfter(at),"预计结束时间必须晚于当前时间");
    ApiException.require(db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled",Long.class,host)==1,"接待人不存在或已停用");
    lock();String code=allocate(at,digits);
    long id=db.queryForObject("INSERT INTO visitors(host_id,name,phone,company,purpose,people,companions,start_at,end_at,plate,note,code,active_code,status,temporary,checked_in_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,host,in.name().trim(),in.phone(),in.company().trim(),in.purpose().trim(),in.people(),in.companions(),in.startAt(),in.endAt(),in.plate(),in.note(),code,code,temporary?"ARRIVED":"WAITING",temporary,temporary?at:null);
    if(temporary)notice(id,host,in.name());auth.audit(a.id(),temporary?"REGISTER_TEMP_VISITOR":"RESERVE_VISITOR",id);return get(id,a);
  }
  void notice(long id,long host,Object name){db.update("INSERT INTO notifications(user_id,message,visitor_id) VALUES(?,?,?) ON CONFLICT (visitor_id) WHERE visitor_id IS NOT NULL DO NOTHING",host,"访客「"+name+"」已到访，请接待（来访 #"+id+"）",id);}
  @PostMapping("/{id}/cancel") @Transactional Object cancel(@PathVariable long id,HttpServletRequest req){var a=Auth.actor(req);lock();var r=get(id,a);if(((Number)r.get("host_id")).longValue()!=a.id())throw new ApiException(403,"仅接待人可取消预约");ApiException.require(r.get("status").equals("WAITING"),"仅待到访预约可取消");db.update("UPDATE visitors SET status='CANCELLED',active_code=NULL WHERE id=?",id);auth.audit(a.id(),"CANCEL_VISITOR",id);return get(id,a);}
  Object arrive(long id,Actor a){var r=get(id,a);ApiException.require(r.get("status").equals("WAITING"),"仅待到访预约可签到，已取消或已过期不能签到");db.update("UPDATE visitors SET status='ARRIVED',checked_in_at=? WHERE id=?",now(),id);notice(id,((Number)r.get("host_id")).longValue(),r.get("name"));auth.audit(a.id(),"CHECKIN_VISITOR",id);return get(id,a);}
  @PostMapping("/{id}/check-in") @Transactional Object checkIn(@PathVariable long id,HttpServletRequest req){var a=Auth.actor(req);a.requireAdmin();lock();return arrive(id,a);}
  record Code(@NotNull @Pattern(regexp="[0-9]{6}") String code){}
  @PostMapping("/check-in") @Transactional Object byCode(@Valid @RequestBody Code in,HttpServletRequest req){var a=Auth.actor(req);a.requireAdmin();lock();var ids=db.queryForList("SELECT id FROM visitors WHERE code=? AND (status='ARRIVED' OR status='WAITING' AND end_at>=?)",Long.class,in.code(),now());ApiException.require(!ids.isEmpty(),"访客码无效、已取消或已过期，请搜索记录核对");return arrive(ids.getFirst(),a);}
  @PostMapping("/{id}/check-out") @Transactional Object checkOut(@PathVariable long id,HttpServletRequest req){var a=Auth.actor(req);a.requireAdmin();lock();var r=get(id,a);ApiException.require(r.get("status").equals("ARRIVED"),"仅已到访记录可签离，未签到不能签离");db.update("UPDATE visitors SET status='LEFT',checked_out_at=?,active_code=NULL WHERE id=?",now(),id);auth.audit(a.id(),"CHECKOUT_VISITOR",id);return get(id,a);}
  @GetMapping("/{id}") Object detail(@PathVariable long id,HttpServletRequest req){return get(id,Auth.actor(req));}
  @GetMapping("/hosts") Object hosts(HttpServletRequest req){Auth.actor(req).requireAdmin();return db.queryForList("SELECT id,name,employee_no FROM users WHERE enabled ORDER BY employee_no");}
  record Filter(LocalDate from,LocalDate to,String status,Long hostId,String keyword,boolean mine,int offset,int limit){}
  List<Map<String,Object>> query(Actor a,Filter f,boolean export){
    ApiException.require(f.offset()>=0&&f.limit()>0&&f.limit()<=200,"分页参数无效，每页最多200条");
    ApiException.require(f.status()==null||STATES.contains(f.status()),"访客状态无效");
    ApiException.require(f.from()==null||f.to()==null||!f.from().isAfter(f.to()),"日期区间无效");hostAllowed(a,f.hostId());
    var args=new ArrayList<Object>();LocalDateTime at=now();args.add(at);String w=" WHERE "+visible(a);
    if(f.mine()){w+=" AND v.host_id=?";args.add(a.id());}
    if(f.hostId()!=null){w+=" AND v.host_id=?";args.add(f.hostId());}
    if(f.from()!=null){w+=" AND v.end_at>=?";args.add(f.from().atStartOfDay());}
    if(f.to()!=null){w+=" AND v.start_at<?";args.add(f.to().plusDays(1).atStartOfDay());}
    if(f.status()!=null){w+=" AND "+effective()+"=?";args.add(at);args.add(f.status());}
    if(f.keyword()!=null&&!f.keyword().isBlank()){w+=" AND (v.name ILIKE ? OR v.company ILIKE ? OR v.code ILIKE ? OR h.name ILIKE ?)";for(int i=0;i<4;i++)args.add("%"+f.keyword()+"%");}
    if(!export){args.add(f.limit());args.add(f.offset());}
    var rs=rows("SELECT v.id,v.host_id,h.name host_name,v.name,v.phone,v.company,v.purpose,v.people,v.companions,v.start_at,v.end_at,v.plate,v.note,v.code,"+effective()+" status,v.temporary,v.checked_in_at,v.checked_out_at FROM visitors v JOIN users h ON h.id=v.host_id"+w+" ORDER BY v.start_at DESC,v.id DESC"+(export?"":" LIMIT ? OFFSET ?"),args.toArray());
    if(!export)rs.forEach(r->r.put("phone",mask((String)r.get("phone"))));return rs;
  }
  @GetMapping Object list(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) String status,@RequestParam(required=false) Long hostId,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="false") boolean mine,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="200") int limit,HttpServletRequest req){return query(Auth.actor(req),new Filter(from,to,status,hostId,keyword,mine,offset,limit),false);}
  @GetMapping("/summary") Object summary(HttpServletRequest req){var a=Auth.actor(req);var today=now().toLocalDate();return db.queryForMap("SELECT count(*) FILTER(WHERE NOT v.temporary AND v.start_at>=? AND v.start_at<?) \"todayReservations\",coalesce(sum(v.people) FILTER(WHERE v.status='ARRIVED'),0) present FROM visitors v WHERE "+visible(a),today.atStartOfDay(),today.plusDays(1).atStartOfDay());}
  static String csv(Object value){String s=value==null?"":LABELS.getOrDefault(value.toString(),value.toString());if(s.stripLeading().matches("(?s)^[=+@\\-\\t\\r\\n].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
  @GetMapping("/export") Object export(@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(required=false) String status,@RequestParam(required=false) Long hostId,@RequestParam(required=false) String keyword,@RequestParam(defaultValue="false") boolean mine,HttpServletRequest req){var a=Auth.actor(req);a.requireAdmin();var rs=query(a,new Filter(from,to,status,hostId,keyword,mine,0,200),true);var out=new StringBuilder("\ufeff访客姓名,手机号,单位,事由,人数,随行人,接待人,预计开始,预计结束,访客码,状态,签到时间,签离时间,车牌号,备注\r\n");for(var r:rs)out.append(java.util.stream.Stream.of("name","phone","company","purpose","people","companions","host_name","start_at","end_at","code","status","checked_in_at","checked_out_at","plate","note").map(k->csv(r.get(k))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");auth.audit(a.id(),"EXPORT_VISITORS",rs.size());return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=visitors.csv").contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).body(out.toString());}
}
