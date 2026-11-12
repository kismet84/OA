package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;

@RestController
@RequestMapping("/api/contracts")
class ContractController {
  @org.springframework.beans.factory.annotation.Autowired
  NotificationService notifications;
  final JdbcTemplate db; final Auth auth; final Clock clock; final AttachmentController attachments;
  ContractController(JdbcTemplate db, Auth auth, Clock clock, AttachmentController attachments) {
    this.db=db; this.auth=auth; this.clock=clock; this.attachments=attachments;
  }
  static final Set<String> TYPES=Set.of("PURCHASE","SALES","SERVICE","LEASE","LABOR","OTHER");
  static final Set<String> STATES=Set.of("DRAFT","PENDING","REJECTED","WITHDRAWN","TO_ARCHIVE","ACTIVE","EXPIRED","TERMINATED");
  static final Map<String,String> LABELS=Map.ofEntries(Map.entry("DRAFT","草稿"),Map.entry("PENDING","审批中"),Map.entry("REJECTED","已驳回"),Map.entry("WITHDRAWN","已撤回"),Map.entry("TO_ARCHIVE","待归档"),Map.entry("ACTIVE","履行中"),Map.entry("EXPIRED","已到期"),Map.entry("TERMINATED","已终止"),Map.entry("PURCHASE","采购"),Map.entry("SALES","销售"),Map.entry("SERVICE","服务"),Map.entry("LEASE","租赁"),Map.entry("LABOR","劳务"),Map.entry("OTHER","其他"));
  LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));}
  static boolean all(Actor a){return a.admin()||a.has("FINANCE");}
  long num(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
  String visibility(Actor a) {
    if(all(a))return "true";
    return "(c.user_id="+a.id()+" OR EXISTS(SELECT 1 FROM users v WHERE v.id=c.user_id AND v.manager_id="+a.id()+")"+
      (a.has("DEPT_HEAD")?" OR c.department_id IN (WITH RECURSIVE ds AS (SELECT id FROM departments WHERE head_id="+a.id()+" UNION SELECT d.id FROM departments d JOIN ds ON d.parent_id=ds.id) SELECT id FROM ds)":"")+")";
  }
  String effective(){return "CASE WHEN c.expiry_date<? AND c.status<>'TERMINATED' THEN 'EXPIRED' ELSE c.status END";}
  List<Map<String,Object>> rows(String sql,Object...args){
    var rs=db.queryForList(sql,args);rs.forEach(r->r.replaceAll((k,v)->v instanceof java.sql.Timestamp t?t.toLocalDateTime():v));return rs;
  }
  Map<String,Object> get(long id,Actor a,boolean lock){
    var rs=rows("SELECT c.*,u.name user_name,d.name department_name FROM contracts c JOIN users u ON u.id=c.user_id JOIN departments d ON d.id=c.department_id WHERE c.id=?"+(lock?" FOR UPDATE OF c":""),id);
    if(rs.isEmpty())throw new ApiException(404,"合同不存在");
    if(db.queryForObject("SELECT count(*) FROM contracts c WHERE c.id=? AND "+visibility(a),Long.class,id)==0)throw new ApiException(403,"无权查看或处理该合同");
    var r=rs.getFirst(); r.put("workflow_status",r.get("status"));
    if(r.get("expiry_date")!=null && ((java.sql.Date)r.get("expiry_date")).toLocalDate().isBefore(today())&&!r.get("status").equals("TERMINATED"))r.put("status","EXPIRED");
    return r;
  }
  void owner(Map<String,Object> r,Actor a){if(num(r,"user_id")!=a.id())throw new ApiException(403,"仅经办人可操作");}
  void state(boolean ok){if(!ok)throw new ApiException(409,"当前合同状态不允许此操作");}
  boolean editable(Map<String,Object> r){return Set.of("DRAFT","REJECTED","WITHDRAWN").contains(r.get("workflow_status"));}
  void notice(long user,long id,String message){notifications.write(user,"合同 #"+id+"："+message, "contract_id",id);}
  void audit(Actor a,String action,long id){auth.audit(a.id(),action,id);}
  void checkFile(UUID file, long user, Long contractId) {
    var uploaded=db.queryForList("SELECT id FROM attachments WHERE id=? AND user_id=? FOR UPDATE",file,user);
    ApiException.require(!uploaded.isEmpty() || contractId!=null && db.queryForObject("SELECT count(*) FROM contract_files WHERE contract_id=? AND attachment_id=?",Long.class,contractId,file)>0,"附件不存在或无权使用");
    ApiException.require(db.queryForObject("SELECT (SELECT count(*) FROM document_file_history WHERE attachment_id=?)+(SELECT count(*) FROM announcement_attachments WHERE attachment_id=?)+(SELECT count(*) FROM requests WHERE attachment_id=?)+(SELECT count(*) FROM expense_attachments WHERE attachment_id=?)+(SELECT count(*) FROM assets WHERE attachment_id=?)",Long.class,file,file,file,file,file)==0,"该附件已用于其他业务，请为合同重新上传");
  }
  record Input(@Size(max=100) String code,@NotBlank @Size(max=200) String name,@NotNull String type,
    @NotBlank @Size(max=200) String counterparty,@Size(max=100) String contact,@Size(max=50) String phone,
    Long userId,@DecimalMin("0") @Digits(integer=12,fraction=2) BigDecimal amount,
    @NotNull LocalDate signedDate,@NotNull LocalDate effectiveDate,LocalDate expiryDate,
    @Size(max=5000) String performance,@Size(max=5000) String note,@Size(max=30) List<@NotNull UUID> attachmentIds){}
  @PostMapping @Transactional Object create(@Valid @RequestBody Input in,HttpServletRequest req){return save(null,in,Auth.actor(req));}
  @PutMapping("/{id}") @Transactional Object update(@PathVariable long id,@Valid @RequestBody Input in,HttpServletRequest req){return save(id,in,Auth.actor(req));}
  Object save(Long id,Input in,Actor a){
    ApiException.require(TYPES.contains(in.type()),"合同类型无效");
    ApiException.require(in.expiryDate()==null||!in.expiryDate().isBefore(in.effectiveDate()),"到期日期不能早于生效日期");
    Map<String,Object> old=null;
    if(id!=null){old=get(id,a,true);owner(old,a);state(editable(old));}
    long user=in.userId()==null?a.id():in.userId();
    if(user!=a.id())a.requireAdmin();
    if(old!=null)ApiException.require(user==num(old,"user_id"),"经办人不可在修改时更换");
    var us=db.queryForList("SELECT department_id FROM users WHERE id=? AND enabled",user);
    ApiException.require(!us.isEmpty(),"经办人不存在或已停用");
    var files=new LinkedHashSet<>(in.attachmentIds()==null?List.<UUID>of():in.attachmentIds());
    for(UUID f:files)checkFile(f,a.id(),id);
    String code=in.code()==null||in.code().isBlank()?(old==null?"HT-"+today().getYear()+"-"+String.format("%06d",db.queryForObject("SELECT nextval('contract_code_seq')",Long.class)):(String)old.get("code")):in.code().trim();
    if(db.queryForObject("SELECT count(*) FROM contracts WHERE code=? AND (?::bigint IS NULL OR id<>?)",Long.class,code,id,id)>0)throw new ApiException(409,"合同编号重复");
    if(id==null)id=db.queryForObject("INSERT INTO contracts(code,name,type,counterparty,contact,phone,user_id,department_id,amount,signed_date,effective_date,expiry_date,performance,note) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",Long.class,code,in.name().trim(),in.type(),in.counterparty().trim(),in.contact(),in.phone(),user,us.getFirst().get("department_id"),in.amount(),in.signedDate(),in.effectiveDate(),in.expiryDate(),in.performance(),in.note());
    else db.update("UPDATE contracts SET code=?,name=?,type=?,counterparty=?,contact=?,phone=?,amount=?,signed_date=?,effective_date=?,expiry_date=?,performance=?,note=? WHERE id=?",code,in.name().trim(),in.type(),in.counterparty().trim(),in.contact(),in.phone(),in.amount(),in.signedDate(),in.effectiveDate(),in.expiryDate(),in.performance(),in.note(),id);
    db.update("DELETE FROM contract_files WHERE contract_id=?",id);
    for(UUID f:files)db.update("INSERT INTO contract_files(contract_id,attachment_id) VALUES(?,?)",id,f);
    audit(a,"SAVE_CONTRACT",id);return Map.of("id",id);
  }
  @DeleteMapping("/{id}") @Transactional Object delete(@PathVariable long id,HttpServletRequest req){var a=Auth.actor(req);var r=get(id,a,true);owner(r,a);state(r.get("workflow_status").equals("DRAFT"));db.update("DELETE FROM contracts WHERE id=?",id);audit(a,"DELETE_CONTRACT",id);return Map.of("message","草稿已删除");}
  String pendingSql(Actor a){return "c.status='PENDING' AND c.user_id<>"+a.id()+" AND EXISTS(SELECT 1 FROM contract_steps s WHERE s.contract_id=c.id AND s.revision=c.revision AND s.step=c.current_step AND (s.approver_id="+a.id()+" OR s.approver_id IS NULL AND "+all(a)+"))";}
  long pending(Actor a){return db.queryForObject("SELECT count(*) FROM contracts c WHERE "+pendingSql(a),Long.class);}
  void notifyNext(long id){
    var r=db.queryForMap("SELECT c.user_id,s.approver_id FROM contracts c JOIN contract_steps s ON s.contract_id=c.id AND s.revision=c.revision AND s.step=c.current_step WHERE c.id=?",id);
    if(r.get("approver_id")!=null)notice(num(r,"approver_id"),id,"待您审批");
    else for(long u:db.queryForList("SELECT id FROM users WHERE enabled AND id<>? AND ( 'FINANCE'=ANY(string_to_array(roles,',')) OR 'ADMIN'=ANY(string_to_array(roles,',')))",Long.class,r.get("user_id")))notice(u,id,"待财务或管理员终审");
  }
  @PostMapping("/{id}/submit") @Transactional Object submit(@PathVariable long id,HttpServletRequest req){
    var a=Auth.actor(req);var r=get(id,a,true);owner(r,a);state(editable(r));
    var u=db.queryForMap("SELECT manager_id FROM users WHERE id=?",r.get("user_id"));
    List<Long> chain=new ArrayList<>();Long manager=(Long)u.get("manager_id");
    if(manager!=null){ApiException.require(manager!=a.id()&&db.queryForObject("SELECT count(*) FROM users WHERE id=? AND enabled",Long.class,manager)==1,"直属上级无效，请联系管理员");chain.add(manager);}
    BigDecimal threshold=db.queryForObject("SELECT contract_threshold FROM workflow_config WHERE id=1",BigDecimal.class);
    if(r.get("amount")!=null&&((BigDecimal)r.get("amount")).compareTo(threshold)>0){
      Long head=db.queryForObject("SELECT head_id FROM departments WHERE id=?",Long.class,r.get("department_id"));
      ApiException.require(head!=null&&head!=a.id()&&auth.user(head).has("DEPT_HEAD"),"请配置有效的部门负责人，禁止自批");
      if(!chain.contains(head))chain.add(head);
    }
    ApiException.require(db.queryForObject("SELECT count(*) FROM users WHERE enabled AND id<>? AND ('FINANCE'=ANY(string_to_array(roles,',')) OR 'ADMIN'=ANY(string_to_array(roles,',')))",Long.class,a.id())>0,"请配置财务或管理员终审人，禁止自批");
    int rev=((Number)r.get("revision")).intValue()+1;
    chain.add(null);for(int n=0;n<chain.size();n++)db.update("INSERT INTO contract_steps(contract_id,revision,step,approver_id) VALUES(?,?,?,?)",id,rev,n+1,chain.get(n));
    db.update("UPDATE contracts SET status='PENDING',revision=?,current_step=1 WHERE id=?",rev,id);
    notice(a.id(),id,"已提交审批");notifyNext(id);audit(a,"SUBMIT_CONTRACT",id);return Map.of("message","合同已提交");
  }
  record Decision(boolean approved,@Size(max=1000) String note){}
  @PostMapping("/{id}/approve") @Transactional Object approve(@PathVariable long id,@Valid @RequestBody Decision in,HttpServletRequest req){
    var a=Auth.actor(req);get(id,a,true);
    if(db.queryForObject("SELECT count(*) FROM contracts c WHERE c.id=? AND "+pendingSql(a),Long.class,id)==0)throw new ApiException(403,"当前不轮到您审批或合同已处理");
    ApiException.require(in.approved()||in.note()!=null&&!in.note().isBlank(),"驳回必须填写原因");
    var r=db.queryForMap("SELECT * FROM contracts WHERE id=?",id);
    db.update("UPDATE contract_steps SET status=?,actor_id=?,note=?,acted_at=? WHERE contract_id=? AND revision=? AND step=?",in.approved()?"APPROVED":"REJECTED",a.id(),in.note(),LocalDateTime.now(clock),id,r.get("revision"),r.get("current_step"));
    int next=((Number)r.get("current_step")).intValue()+1;
    boolean last=db.queryForObject("SELECT count(*) FROM contract_steps WHERE contract_id=? AND revision=? AND step=?",Long.class,id,r.get("revision"),next)==0;
    String status=!in.approved()?"REJECTED":last?"TO_ARCHIVE":"PENDING";
    db.update("UPDATE contracts SET status=?,current_step=? WHERE id=?",status,status.equals("PENDING")?next:r.get("current_step"),id);
    notice(num(r,"user_id"),id,in.approved()?(last?"审批通过，待上传盖章版归档":"本级审批通过"):"已驳回："+in.note());
    if(status.equals("PENDING"))notifyNext(id);audit(a,in.approved()?"APPROVE_CONTRACT":"REJECT_CONTRACT",id);return Map.of("message","审批已处理");
  }
  @PostMapping("/{id}/withdraw") @Transactional Object withdraw(@PathVariable long id,HttpServletRequest req){var a=Auth.actor(req);var r=get(id,a,true);owner(r,a);state(r.get("workflow_status").equals("PENDING"));db.update("UPDATE contracts SET status='WITHDRAWN' WHERE id=?",id);notice(a.id(),id,"已撤回");audit(a,"WITHDRAW_CONTRACT",id);return Map.of("message","已撤回");}
  record Archive(@NotNull @Size(min=1,max=30) List<@NotNull UUID> attachmentIds){}
  @PostMapping("/{id}/archive") @Transactional Object archive(@PathVariable long id,@Valid @RequestBody Archive in,HttpServletRequest req){
    var a=Auth.actor(req);var r=get(id,a,true);if(num(r,"user_id")!=a.id()&&!a.admin())throw new ApiException(403,"仅经办人或管理员可归档");state(r.get("workflow_status").equals("TO_ARCHIVE"));
    for(UUID f:new LinkedHashSet<>(in.attachmentIds())){
      ApiException.require(db.queryForObject("SELECT count(*) FROM attachments WHERE id=? AND user_id=?",Long.class,f,a.id())==1,"盖章版须为本人上传的附件");
      checkFile(f,a.id(),id);
      db.update("INSERT INTO contract_files VALUES(?,?,true) ON CONFLICT(contract_id,attachment_id) DO UPDATE SET sealed=true",id,f);
    }
    db.update("UPDATE contracts SET status='ACTIVE',archived_at=? WHERE id=?",LocalDateTime.now(clock),id);notice(num(r,"user_id"),id,"已归档，进入履行中");audit(a,"ARCHIVE_CONTRACT",id);return Map.of("message","合同已归档");
  }
  record Reason(@NotBlank @Size(max=1000) String reason){}
  @PostMapping("/{id}/terminate") @Transactional Object terminate(@PathVariable long id,@Valid @RequestBody Reason in,HttpServletRequest req){var a=Auth.actor(req);var r=get(id,a,true);if(num(r,"user_id")!=a.id()&&!a.admin())throw new ApiException(403,"仅经办人或管理员可终止");state(!Set.of("DRAFT","TERMINATED").contains(r.get("workflow_status")));db.update("UPDATE contracts SET status='TERMINATED',termination_reason=? WHERE id=?",in.reason(),id);notice(num(r,"user_id"),id,"已终止："+in.reason());audit(a,"TERMINATE_CONTRACT",id);return Map.of("message","合同已终止");}
  @GetMapping("/{id}") Object detail(@PathVariable long id,HttpServletRequest req){var a=Auth.actor(req);var r=get(id,a,false);r.put("isOwner",num(r,"user_id")==a.id());r.put("canArchive",num(r,"user_id")==a.id()||a.admin());r.put("canApprove",db.queryForObject("SELECT count(*) FROM contracts c WHERE c.id=? AND "+pendingSql(a),Long.class,id)>0);r.put("files",rows("SELECT f.*,a.original_name,a.media_type FROM contract_files f JOIN attachments a ON a.id=f.attachment_id WHERE contract_id=? ORDER BY sealed,original_name",id));r.put("steps",rows("SELECT s.*,u.name approver_name,v.name actor_name FROM contract_steps s LEFT JOIN users u ON u.id=s.approver_id LEFT JOIN users v ON v.id=s.actor_id WHERE contract_id=? ORDER BY revision,step",id));return r;}
  record Filter(String status,String type,Long departmentId,String counterparty,LocalDate expiryFrom,LocalDate expiryTo,String scope,int offset,int limit){}
  String where(Actor a,Filter f,List<Object> args){
    ApiException.require(f.offset()>=0&&f.limit()>0&&f.limit()<=200,"分页参数无效，每页最多200条");
    ApiException.require(f.status()==null||STATES.contains(f.status()),"合同状态无效");ApiException.require(f.type()==null||TYPES.contains(f.type()),"合同类型无效");ApiException.require(Set.of("visible","mine","pending").contains(f.scope()),"查询范围无效");
    ApiException.require(f.expiryFrom()==null||f.expiryTo()==null||!f.expiryFrom().isAfter(f.expiryTo()),"到期区间无效");
    String s=" WHERE "+visibility(a);
    if(f.scope().equals("mine")){s+=" AND c.user_id=?";args.add(a.id());}
    if(f.scope().equals("pending"))s+=" AND "+pendingSql(a);
    if(f.status()!=null){s+=" AND "+effective()+"=?";args.add(today());args.add(f.status());}
    if(f.type()!=null){s+=" AND c.type=?";args.add(f.type());}
    if(f.departmentId()!=null){s+=" AND c.department_id=?";args.add(f.departmentId());}
    if(f.counterparty()!=null){s+=" AND c.counterparty ILIKE ?";args.add("%"+f.counterparty()+"%");}
    if(f.expiryFrom()!=null){s+=" AND c.expiry_date>=?";args.add(f.expiryFrom());}
    if(f.expiryTo()!=null){s+=" AND c.expiry_date<=?";args.add(f.expiryTo());}return s;
  }
  @GetMapping Object list(@RequestParam(required=false) String status,@RequestParam(required=false) String type,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) String counterparty,@RequestParam(required=false) LocalDate expiryFrom,@RequestParam(required=false) LocalDate expiryTo,@RequestParam(defaultValue="visible") String scope,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="200") int limit,HttpServletRequest req){return query(Auth.actor(req),new Filter(status,type,departmentId,counterparty,expiryFrom,expiryTo,scope,offset,limit),false);}
  List<Map<String,Object>> query(Actor a,Filter f,boolean export){var args=new ArrayList<Object>();args.add(today());String w=where(a,f,args);if(!export){args.add(f.limit());args.add(f.offset());}return rows("SELECT c.*,c.status workflow_status,"+effective()+" status,u.name user_name,d.name department_name FROM contracts c JOIN users u ON u.id=c.user_id JOIN departments d ON d.id=c.department_id"+w+" ORDER BY c.id DESC"+(export?"":" LIMIT ? OFFSET ?"),args.toArray());}
  @GetMapping("/summary") Object summary(HttpServletRequest req){var a=Auth.actor(req);return Map.of("expiring30",db.queryForObject("SELECT count(*) FROM contracts c WHERE "+visibility(a)+" AND c.status IN ('TO_ARCHIVE','ACTIVE') AND c.expiry_date BETWEEN ? AND ?",Long.class,today(),today().plusDays(30)));}
  String csv(Object v){String s=v==null?"":LABELS.getOrDefault(v.toString(),v.toString());if(s.matches("^[=+@\\-\\t\\r].*"))s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
  @GetMapping("/export") Object export(@RequestParam(required=false) String status,@RequestParam(required=false) String type,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) String counterparty,@RequestParam(required=false) LocalDate expiryFrom,@RequestParam(required=false) LocalDate expiryTo,HttpServletRequest req){var a=Auth.actor(req);if(!all(a))throw new ApiException(403,"仅管理员或财务可导出");var rs=query(a,new Filter(status,type,departmentId,counterparty,expiryFrom,expiryTo,"visible",0,200),true);var out=new StringBuilder("\ufeff合同编号,合同名称,类型,对方单位,经办人,部门,金额,签订日期,生效日期,到期日期,状态\r\n");for(var r:rs){out.append(java.util.stream.Stream.of("code","name","type","counterparty","user_name","department_name","amount","signed_date","effective_date","expiry_date","status").map(k->csv(r.get(k))).collect(java.util.stream.Collectors.joining(","))).append("\r\n");}auth.audit(a.id(),"EXPORT_CONTRACTS",rs.size());return ResponseEntity.ok().header("Content-Disposition","attachment; filename=contracts.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  @GetMapping("/{id}/files/{file}") Object file(@PathVariable long id,@PathVariable UUID file,@RequestParam(defaultValue="false") boolean preview,HttpServletRequest req)throws java.io.IOException{
    var a=Auth.actor(req);get(id,a,false);var rs=db.queryForList("SELECT a.* FROM contract_files f JOIN attachments a ON a.id=f.attachment_id WHERE contract_id=? AND attachment_id=?",id,file);if(rs.isEmpty())throw new ApiException(404,"合同附件不存在");var r=rs.getFirst();var p=attachments.path(file);if(!java.nio.file.Files.isRegularFile(p))throw new ApiException(404,"附件文件缺失");audit(a,preview?"PREVIEW_CONTRACT_FILE":"DOWNLOAD_CONTRACT_FILE",id);
    return ResponseEntity.ok().header("X-Content-Type-Options","nosniff").header("Cache-Control","no-store").header("Content-Disposition",(preview?ContentDisposition.inline():ContentDisposition.attachment()).filename((String)r.get("original_name"),java.nio.charset.StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType((String)r.get("media_type"))).body(new FileSystemResource(p));
  }
  @org.springframework.scheduling.annotation.Scheduled(cron="0 0 8 * * *",zone="Asia/Shanghai")
  @Transactional public void reminders(){
    for(int node:List.of(30,7))for(var r:db.queryForList("SELECT id,user_id FROM contracts WHERE status IN ('TO_ARCHIVE','ACTIVE') AND expiry_date BETWEEN ? AND ?",today(),today().plusDays(node))){long id=num(r,"id");if(db.update("INSERT INTO contract_reminders(contract_id,node) VALUES(?,?) ON CONFLICT DO NOTHING",id,node)==1)notice(num(r,"user_id"),id,"到期提醒："+node+"天内到期，请及时处理");}
  }
}
