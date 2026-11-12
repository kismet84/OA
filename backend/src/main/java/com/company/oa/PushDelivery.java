package com.company.oa;

import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.jdbc.core.JdbcTemplate;

interface PushSender {
  enum Result { SENT, INVALID, SKIPPED }
  Result send(String token, NotificationService.Created notice) throws Exception;
}

@Configuration
@EnableAsync
class PushConfiguration {
  @Bean(name="pushExecutor")
  ThreadPoolTaskExecutor pushExecutor() {
    var executor=new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(1000);
    executor.setThreadNamePrefix("fcm-");
    // Saturation drops only push delivery; the committed message remains available.
    executor.setRejectedExecutionHandler((r,e)->LoggerFactory.getLogger(PushDelivery.class).warn("推送队列已满，请通过消息中心查看通知"));
    return executor;
  }
}

@Component
class PushDelivery {
  final JdbcTemplate db;
  final PushSender sender;
  PushDelivery(JdbcTemplate db, PushSender sender) { this.db=db; this.sender=sender; }
  @Async("pushExecutor")
  @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT, fallbackExecution=true)
  public void deliver(NotificationService.Created notice) {
    try {
      var devices=db.queryForList("SELECT p.id,p.token,p.updated_at FROM push_tokens p JOIN users u ON u.id=p.user_id"
          + " WHERE p.user_id=? AND p.platform='ANDROID' AND u.enabled=true AND (p.session_id IS NULL OR EXISTS"
          + " (SELECT 1 FROM login_sessions s WHERE s.id=p.session_id AND s.user_id=p.user_id AND s.revoked_at IS NULL"
          + " AND s.expires_at>now() AND s.session_version=u.session_version))",notice.userId());
      for(var device:devices) {
        try {
          if(sender.send((String)device.get("token"),notice)==PushSender.Result.INVALID)
            db.update("DELETE FROM push_tokens WHERE id=? AND updated_at=?",device.get("id"),device.get("updated_at"));
        } catch(Exception e) {
          // Never log exception text: SDK errors may contain tokens or credential details.
          LoggerFactory.getLogger(getClass()).warn("安卓推送失败，通知 {} 仍可在消息中心查看",notice.data().get("notification_id"));
        }
      }
    } catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("推送设备读取失败，消息中心不受影响"); }
  }
}
