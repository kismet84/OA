package com.company.oa;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

record Actor(long id, String name, String roles, boolean mustChange, int version) {
  boolean admin() {
    return has("ADMIN");
  }

  boolean has(String role) {
    return Arrays.asList(roles.split(",")).contains(role);
  }

  void requireAdmin() {
    if (!admin()) throw new ApiException(403, "仅管理员可操作");
  }
}

@Component
class Auth implements HandlerInterceptor {
  final JdbcTemplate db;
  final StringRedisTemplate redis;
  final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  Auth(JdbcTemplate db, StringRedisTemplate redis) {
    this.db = db;
    this.redis = redis;
  }

  static String validPassword(String password) {
    ApiException.require(
        password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72,
        "密码 UTF-8 编码长度不能超过 72 字节");
    return password;
  }

  Actor user(long id) {
    var rows = db.queryForList("SELECT * FROM users WHERE id=? AND enabled=true", id);
    if (rows.isEmpty()) throw new ApiException(401, "账号已停用或会话已失效");
    var u = rows.getFirst();
    return new Actor(
        id,
        (String) u.get("name"),
        (String) u.get("roles"),
        (boolean) u.get("must_change_password"),
        (int) u.get("session_version"));
  }

  static Actor actor(HttpServletRequest request) {
    return (Actor) request.getAttribute("actor");
  }

  static String sessionId(String token) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static String token(HttpServletRequest request) {
    String h = request.getHeader("Authorization");
    return h != null && h.startsWith("Bearer ") ? h.substring(7) : "";
  }

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (!(handler instanceof org.springframework.web.method.HandlerMethod method)) return true;
    Class<?> type = method.getBeanType();
    if (!type.getPackageName().equals("com.company.oa")) return true;
    String name = method.getMethod().getName();
    boolean authEndpoint = AuthController.class.isAssignableFrom(type);
    if ((authEndpoint && name.equals("login"))
        || (RequestController.class.isAssignableFrom(type) && name.equals("health"))) return true;
    String t = token(req);
    if (t.isBlank()) throw new ApiException(401, "请先登录");
    String session = redis.opsForValue().get("session:" + t);
    if (session == null) throw new ApiException(401, "会话已过期，请重新登录");
    String[] parts = session.split(":");
    Actor a = user(Long.parseLong(parts[0]));
    if (a.version() != Integer.parseInt(parts[1])) throw new ApiException(401, "会话已失效，请重新登录");
    if (db.queryForObject(
            "SELECT count(*) FROM login_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL"
                + " AND expires_at>now() AND session_version=?",
            Long.class,
            sessionId(t),
            a.id(),
            a.version())
        != 1) throw new ApiException(401, "设备会话已失效，请重新登录");
    req.setAttribute("actor", a);
    if (a.mustChange() && !(authEndpoint && Set.of("me", "password", "logout").contains(name)))
      throw new ApiException(403, "首次登录必须修改密码");
    if (org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
        type, AdminOnly.class)) a.requireAdmin();
    return true;
  }

  void audit(long actor, String action, Object target) {
    db.update(
        "INSERT INTO audit_logs(actor_id,action,target) VALUES(?,?,?)",
        actor,
        action,
        String.valueOf(target));
  }

  long throttle(String key) {
    return redis.execute(
        new DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],900) end;"
                + " return n",
            Long.class),
        List.of(key));
  }
}

@RestController
@RequestMapping("/api/auth")
class AuthController {
  final Auth auth;
  final JdbcTemplate db;
  final Environment env;

  AuthController(Auth auth, JdbcTemplate db, Environment env) {
    this.auth = auth;
    this.db = db;
    this.env = env;
  }

  record Login(
      @NotBlank @Size(max = 80) String login,
      @NotBlank @Size(max = 128) String password,
      @Size(max = 100) String deviceName,
      @Size(max = 30) String platform) {}

  record Password(
      @NotBlank @Size(max = 72) String oldPassword,
      @NotBlank @Size(min = 10, max = 72) String newPassword) {}

  @PostMapping("/login")
  Object login(@Valid @RequestBody Login input, HttpServletRequest req) {
    String key = "login:" + input.login();
    String ipKey = "login-ip:" + req.getRemoteAddr();
    if (auth.throttle(ipKey) > 100) throw new ApiException(429, "登录尝试过多，请 15 分钟后重试");
    String attempts = auth.redis.opsForValue().get(key);
    if (attempts != null && Long.parseLong(attempts) >= 5)
      throw new ApiException(429, "连续登录失败，请 15 分钟后重试");
    var rows =
        db.queryForList(
            "SELECT * FROM users WHERE employee_no=? OR phone=?", input.login(), input.login());
    var u = rows.isEmpty() ? null : rows.getFirst();
    if (u == null
        || !(boolean) u.get("enabled")
        || !(input.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
            && auth.encoder.matches(input.password(), (String) u.get("password_hash")))) {
      auth.throttle(key);
      throw new ApiException(401, "账号或密码错误，或账号已停用");
    }
    auth.redis.delete(key);
    String token = UUID.randomUUID().toString() + UUID.randomUUID();
    auth.redis
        .opsForValue()
        .set("session:" + token, u.get("id") + ":" + u.get("session_version"), Duration.ofHours(8));
    db.update(
        "INSERT INTO login_sessions(id,user_id,session_version,device_name,platform,expires_at)"
            + " VALUES(?,?,?,?,?,now()+interval '8 hours')",
        Auth.sessionId(token),
        u.get("id"),
        u.get("session_version"),
        input.deviceName() == null || input.deviceName().isBlank()
            ? "未命名设备"
            : input.deviceName().trim(),
        input.platform() == null || input.platform().isBlank() ? "未知平台" : input.platform().trim());
    auth.redis
        .opsForValue()
        .set("session-id:" + Auth.sessionId(token), "active", Duration.ofHours(8));
    auth.audit(((Number) u.get("id")).longValue(), "LOGIN", u.get("id"));
    return Map.of("token", token, "user", auth.user(((Number) u.get("id")).longValue()));
  }

  @GetMapping("/me")
  Object me(HttpServletRequest req) {
    return Map.of(
        "user",
        Auth.actor(req),
        "development",
        Arrays.asList(
                env.getActiveProfiles().length == 0
                    ? env.getDefaultProfiles()
                    : env.getActiveProfiles())
            .contains("dev"));
  }

  @PostMapping("/logout")
  Object logout(HttpServletRequest req) {
    db.update(
        "UPDATE login_sessions SET revoked_at=now() WHERE id=?", Auth.sessionId(Auth.token(req)));
    auth.redis.delete("session-id:" + Auth.sessionId(Auth.token(req)));
    auth.redis.delete("session:" + Auth.token(req));
    auth.audit(Auth.actor(req).id(), "LOGOUT", Auth.actor(req).id());
    return Map.of("message", "已退出");
  }

  @PostMapping("/password")
  @Transactional
  Object password(@Valid @RequestBody Password input, HttpServletRequest req) {
    Actor a = Auth.actor(req);
    String old =
        db.queryForObject(
            "SELECT password_hash FROM users WHERE id=? FOR UPDATE", String.class, a.id());
    ApiException.require(
        auth.encoder.matches(Auth.validPassword(input.oldPassword()), old), "原密码不正确");
    ApiException.require(!auth.encoder.matches(input.newPassword(), old), "新密码不能与原密码相同");
    db.update(
        "UPDATE users SET"
            + " password_hash=?,must_change_password=false,session_version=session_version+1 WHERE"
            + " id=?",
        auth.encoder.encode(Auth.validPassword(input.newPassword())),
        a.id());
    auth.audit(a.id(), "CHANGE_PASSWORD", a.id());
    return Map.of("message", "密码已修改，请重新登录");
  }
}

@org.springframework.context.annotation.Configuration
class WebConfig implements WebMvcConfigurer {
  final Auth auth;

  WebConfig(Auth auth) {
    this.auth = auth;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(auth);
  }

  @Value("${oa.origins}")
  String origins;

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(origins.split(","))
        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
        .allowedHeaders("Authorization", "Content-Type")
        .exposedHeaders("Content-Disposition");
  }
}

@Component
class Bootstrap implements ApplicationRunner {
  final JdbcTemplate db;
  final Auth auth;
  final Environment env;
  final String password;

  Bootstrap(
      JdbcTemplate db,
      Auth auth,
      Environment env,
      @Value("${oa.bootstrap-password}") String password) {
    this.db = db;
    this.auth = auth;
    this.env = env;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (db.queryForObject("SELECT count(*) FROM users", Long.class) > 0) return;
    boolean dev =
        Arrays.asList(
                env.getActiveProfiles().length == 0
                    ? env.getDefaultProfiles()
                    : env.getActiveProfiles())
            .contains("dev");
    String pw = password.isBlank() && dev ? "Admin@2026Dev" : password;
    if (pw.length() < 10) throw new IllegalStateException("生产环境首次启动必须设置至少 10 位 BOOTSTRAP_PASSWORD");
    Long dept =
        db.queryForObject("INSERT INTO departments(name) VALUES('公司') RETURNING id", Long.class);
    db.update(
        "INSERT INTO users(employee_no,name,password_hash,roles,department_id)"
            + " VALUES('admin','系统管理员',?,'ADMIN,EMPLOYEE',?)",
        auth.encoder.encode(Auth.validPassword(pw)),
        dept);
  }
}
