package com.company.oa;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.*;
import com.google.firebase.messaging.*;
import java.nio.file.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class FcmPushSender implements PushSender {
  final String credentials;
  private FirebaseMessaging messaging;
  FcmPushSender(@Value("${oa.fcm-credentials:}") String credentials) {
    this.credentials=credentials;
    if(credentials.isBlank()) LoggerFactory.getLogger(getClass()).info("未配置 FCM 凭证，系统通知仅保存至消息中心");
  }
  synchronized FirebaseMessaging messaging() throws Exception {
    if(messaging==null) {
      try(var stream=Files.newInputStream(Path.of(credentials))) {
        var options=FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(stream))
            .setConnectTimeout(5000).setReadTimeout(10000).build();
        messaging=FirebaseMessaging.getInstance(FirebaseApp.initializeApp(options,"oa-push"));
      }
    }
    return messaging;
  }
  @Override public Result send(String token, NotificationService.Created notice) throws Exception {
    if(credentials.isBlank()) {
      LoggerFactory.getLogger(getClass()).debug("未配置 FCM 凭证，仅保存消息中心通知");
      return Result.SKIPPED;
    }
    var message=Message.builder().setToken(token)
        .setNotification(Notification.builder().setTitle(notice.message()).setBody(notice.message()).build())
        .putAllData(notice.data()).setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build()).build();
    try { messaging().send(message); return Result.SENT; }
    catch(FirebaseMessagingException e) {
      if(e.getMessagingErrorCode()==MessagingErrorCode.UNREGISTERED) return Result.INVALID;
      throw e;
    }
  }
}
