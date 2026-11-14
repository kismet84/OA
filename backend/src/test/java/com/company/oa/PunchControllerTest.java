package com.company.oa;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PunchControllerTest {
  final PunchService service = mock(PunchService.class);
  final Actor actor = new Actor(8, "员工", "EMPLOYEE", false, 0);
  final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(
              new PunchController(
                  service, mock(JdbcTemplate.class), mock(WorkbenchController.class), new ClientIpResolver("127.0.0.1")))
          .setControllerAdvice(new ApiErrors())
          .build();

  MockHttpServletRequestBuilder request(String remote) {
    return post("/api/punches")
        .with(
            req -> {
              req.setRemoteAddr(remote);
              return req;
            })
        .requestAttr("actor", actor)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"kind\":\"NORMAL\",\"latitude\":31.23,\"longitude\":121.47,\"accuracy\":12}");
  }

  @Test
  void trustedProxyUsesValidatedAndNormalizedDedicatedHeader() throws Exception {
    mvc.perform(
            request("127.0.0.1")
                .header("X-OA-Client-IP", "2001:db8::12")
                .header("X-Forwarded-For", "203.0.113.99"))
        .andExpect(status().isOk());
    verify(service)
        .record(
            eq(actor), eq(8L), any(), eq(AdminController.normalizeIp("2001:db8::12")), eq(false));
  }

  @Test
  void untrustedSourceIgnoresBothForwardingHeaders() throws Exception {
    mvc.perform(
            request("192.0.2.10")
                .header("X-OA-Client-IP", "203.0.113.12")
                .header("X-Forwarded-For", "203.0.113.99"))
        .andExpect(status().isOk());
    verify(service).record(eq(actor), eq(8L), any(), eq("192.0.2.10"), eq(false));
  }

  @Test
  void trustedProxyCannotFallBackToLoopbackOrForwardedFor() throws Exception {
    mvc.perform(request("127.0.0.1").header("X-Forwarded-For", "203.0.113.12"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("可信反向代理必须传递真实客户端 IP"));
    for (String invalid :
        new String[] {"", "example.com", "999.1.1.1", "203.0.113.12, 192.0.2.1"}) {
      mvc.perform(request("127.0.0.1").header("X-OA-Client-IP", invalid))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }
}
