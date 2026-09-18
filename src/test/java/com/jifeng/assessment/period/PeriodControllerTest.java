// 模块用途：PeriodController 集成测试——覆盖周期状态机端点，验证已删除的 start 端点返回 404
// 依赖文件：PeriodController.java, SecurityConfig.java
// 修改注意：@WithMockUser模拟角色，PUT 需 with(csrf())
package com.jifeng.assessment.period;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PeriodControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ======== 已删除的 start 端点 ========

    // 功能：PUT /periods/{id}/start 已删除——发起考核统一走 POST /tasks/{id}/launch，
    //   残留的 start 端点（分歧死路径，会把周期卡在 ONGOING 且 0 任务）应返回 404
    @Test
    @WithMockUser(roles = "ADMIN")
    void startEndpointShouldReturnNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/periods/any-period-id/start").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
