package com.company.oa;

import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** The only notification writer. RETURNING also excludes conflict-skipped visitor rows. */
@Service
class NotificationService {
  final JdbcTemplate db;
  final ApplicationEventPublisher events;
  NotificationService(JdbcTemplate db, ApplicationEventPublisher events) { this.db=db; this.events=events; }
  record Created(long userId, String message, Map<String,String> data) {}
  void write(Object user, String message) { write(user,message,null,null); }
  void write(Object user, String message, String link, Object id) {
    if (link != null && !Set.of("request_id","meeting_id","task_id","document_id","asset_request_id","contract_id","visitor_id").contains(link))
      throw new IllegalArgumentException("无效通知关联字段");
    message=message.substring(0,Math.min(message.length(),500));
    String sql="INSERT INTO notifications(user_id,message"+(link==null?"":","+link)+") VALUES(?,?"+(link==null?"":",?")+")";
    if ("visitor_id".equals(link)) sql+=" ON CONFLICT (visitor_id) WHERE visitor_id IS NOT NULL DO NOTHING";
    var rows=db.queryForList(sql+" RETURNING *", link==null?new Object[]{user,message}:new Object[]{user,message,id});
    for(var row:rows) {
      Map<String,String> data=new HashMap<>();
      data.put("notification_id",row.get("id").toString());
      for(String key:List.of("request_id","meeting_id","task_id","document_id","asset_request_id","contract_id","visitor_id"))
        if(row.get(key)!=null) data.put(key,row.get(key).toString());
      events.publishEvent(new Created(((Number)user).longValue(),message,Map.copyOf(data)));
    }
  }
}
