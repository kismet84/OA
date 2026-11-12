package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/push/tokens")
class PushController {
  final JdbcTemplate db;
  PushController(JdbcTemplate db) { this.db = db; }
  record Token(@NotBlank @Size(max=2048) @Pattern(regexp="[!-~]+") String token) {}
  record Registration(@NotBlank @Size(max=2048) @Pattern(regexp="[!-~]+") String token,
      @NotNull @Pattern(regexp="ANDROID") String platform, @Size(max=100) String deviceName) {}

  @PostMapping
  Object register(@Valid @RequestBody Registration in, HttpServletRequest req) {
    db.update("INSERT INTO push_tokens(user_id,token,platform,device_name,session_id) VALUES(?,?,?,?,?)"
        + " ON CONFLICT(token) DO UPDATE SET user_id=excluded.user_id,platform=excluded.platform,"
        + " device_name=excluded.device_name,session_id=excluded.session_id,updated_at=now()",
        Auth.actor(req).id(), in.token(), in.platform(), in.deviceName(), Auth.sessionId(Auth.token(req)));
    return Map.of("message", "推送设备已登记");
  }
  @DeleteMapping
  Object unregister(@Valid @RequestBody Token in, HttpServletRequest req) {
    db.update("DELETE FROM push_tokens WHERE user_id=? AND token=?", Auth.actor(req).id(), in.token());
    return Map.of("message", "推送设备已注销");
  }
}
