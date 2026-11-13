package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class ApprovalUrgeController {
  final ApprovalUrgeService service;
  final org.springframework.jdbc.core.JdbcTemplate db;
  final Auth auth;

  ApprovalUrgeController(
      ApprovalUrgeService s, org.springframework.jdbc.core.JdbcTemplate db, Auth auth) {
    service = s;
    this.db = db;
    this.auth = auth;
  }

  @PostMapping("/requests/{id}/urge")
  Object request(@PathVariable long id, HttpServletRequest r) {
    return service.manual("REQUEST", id, Auth.actor(r));
  }

  @PostMapping("/contracts/{id}/urge")
  Object contract(@PathVariable long id, HttpServletRequest r) {
    return service.manual("CONTRACT", id, Auth.actor(r));
  }

  @PostMapping("/assets/requests/{id}/urge")
  Object asset(@PathVariable long id, HttpServletRequest r) {
    return service.manual("ASSET", id, Auth.actor(r));
  }

  @GetMapping("/admin/urge-config")
  Object config(HttpServletRequest r) {
    Auth.actor(r).requireAdmin();
    return service.config();
  }

  record Config(
      @Min(1) @Max(10080) int urge_cooldown_minutes,
      boolean auto_urge_enabled,
      @Min(1) @Max(8760) int auto_urge_hours,
      @Min(0) @Max(8760) int auto_urge_repeat_hours) {}

  @PutMapping("/admin/urge-config")
  @Transactional
  Object save(@Valid @RequestBody Config in, HttpServletRequest r) {
    var a = Auth.actor(r);
    a.requireAdmin();
    db.update(
        "UPDATE workflow_config SET"
            + " urge_cooldown_minutes=?,auto_urge_enabled=?,auto_urge_hours=?,auto_urge_repeat_hours=?"
            + " WHERE id=1",
        in.urge_cooldown_minutes(),
        in.auto_urge_enabled(),
        in.auto_urge_hours(),
        in.auto_urge_repeat_hours());
    auth.audit(a.id(), "SAVE_URGE_CONFIG", 1);
    return service.config();
  }
}

@RestController
@Profile("dev")
class DevAutoUrgeController {
  final ApprovalUrgeService service;

  DevAutoUrgeController(ApprovalUrgeService s) {
    service = s;
  }

  @PostMapping("/api/admin/dev/run-auto-urge")
  Object run(@Valid @RequestBody(required = false) DevRun in, HttpServletRequest r) {
    Auth.actor(r).requireAdmin();
    return Map.of(
        "sent", in == null ? service.auto() : service.autoForDev(in.now(), in.requestId()));
  }

  record DevRun(@NotNull java.time.Instant now, long requestId) {}
}
