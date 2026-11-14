package com.company.oa;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ClientIpResolver {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClientIpResolver.class);
  final Set<String> trustedProxies;

  ClientIpResolver(@Value("${oa.trusted-proxies:}") String proxies) {
    trustedProxies = new HashSet<>(Arrays.asList(proxies.split(",")));
  }

  String resolve(HttpServletRequest req, boolean strict) {
    String ip = req.getRemoteAddr();
    if (trustedProxies.contains(ip)) {
      String forwarded = req.getHeader("X-OA-Client-IP");
      if (!AdminController.validIp(forwarded)) {
        if (strict) throw new ApiException(400, "可信反向代理必须传递真实客户端 IP");
        LOG.warn("Trusted login proxy missing valid X-OA-Client-IP; using proxy-unknown bucket");
        return "proxy-unknown";
      }
      ip = forwarded;
    }
    return AdminController.normalizeIp(ip);
  }
}
