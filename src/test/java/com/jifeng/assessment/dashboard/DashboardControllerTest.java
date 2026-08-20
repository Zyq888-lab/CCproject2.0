// 模块用途：DashboardController 集成测试——覆盖权限校验、响应结构验证
// 依赖文件：DashboardController.java, DashboardService.java, SecurityConfig.java
// 修改注意：@WithMockUser模拟角色，每个测试独立回滚
package com.jifeng.assessment.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ======== config-progress 权限测试 ========

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldReturnConfigProgressAsAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/config-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(5));
    }

    @Test
    @WithMockUser(roles = "PD")
    void shouldRejectConfigProgressForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/config-progress"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectConfigProgressUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/config-progress"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldReturnConfigProgressItemStructure() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/config-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].key").isString())
                .andExpect(jsonPath("$.data[0].label").isString())
                .andExpect(jsonPath("$.data[0].count").isNumber())
                .andExpect(jsonPath("$.data[0].status").isString())
                .andExpect(jsonPath("$.data[0].link").isString());
    }

    // ======== diff-report 权限测试 ========

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldReturnDiffReportAsAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/diff-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "PM")
    void shouldRejectDiffReportForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/diff-report"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectDiffReportUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/diff-report"))
                .andExpect(status().isUnauthorized());
    }
}
