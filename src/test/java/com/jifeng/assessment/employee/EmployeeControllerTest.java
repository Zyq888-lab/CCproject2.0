// 模块用途：EmployeeController 权限集成测试——覆盖员工列表接口对 PM 的只读放开与写权限保留
// 依赖文件：EmployeeController.java, SecurityConfig.java
// 修改注意：@WithMockUser模拟角色，GET 无需 CSRF，POST 需 with(csrf())
package com.jifeng.assessment.employee;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // ======== list 只读权限（PM 放开，用于角色分配下拉框） ========

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldAllowAdminToListEmployees() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PM")
    void shouldAllowPmToListEmployees() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldRejectOtherRoleToListEmployees() throws Exception {
        mockMvc.perform(get("/api/v1/employees"))
                .andExpect(status().isForbidden());
    }

    // ======== create 写权限（仍仅 ADMIN，PM 不得新增员工） ========

    @Test
    @WithMockUser(roles = "PM")
    void shouldRejectPmCreateEmployee() throws Exception {
        mockMvc.perform(post("/api/v1/employees")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
