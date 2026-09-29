package com.multiregion.platform.web;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {DemoStatusResource.class, AdminResource.class})
@Import(DemoCorsConfig.class)
class DemoCorsConfigTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AuroraTopologyGateway topologyGateway;

    @MockitoBean
    FailoverControl failoverControl;

    @MockitoBean
    MultiRegionConfig config;

    @Test
    void demoStatusAllowsCrossOriginGet() throws Exception {
        when(config.awsRegion()).thenReturn("us-east-1");
        when(config.regionRole()).thenReturn("primary");
        when(topologyGateway.isDatabaseConnected()).thenReturn(true);
        when(topologyGateway.currentWriter()).thenReturn("postgres-us");

        mockMvc.perform(get("/demo/status").header(HttpHeaders.ORIGIN, "http://example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://example.com"));
    }

    @Test
    void adminPreflightAllowsPost() throws Exception {
        mockMvc.perform(options("/admin/failover-activate")
                        .header(HttpHeaders.ORIGIN, "http://example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://example.com"));
    }
}
