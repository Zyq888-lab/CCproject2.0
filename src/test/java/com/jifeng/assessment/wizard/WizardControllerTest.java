// 模块用途：WizardController 集成测试——覆盖9个端点、权限校验、入参验证
// 依赖文件：WizardController.java, WizardService.java, SecurityConfig.java
// 修改注意：POST/PUT需携带CSRF token，@WithMockUser模拟角色，每个测试独立回滚
package com.jifeng.assessment.wizard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WizardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ========================================
    // 进度查询测试
    // ========================================

    // 功能：ADMIN角色查询向导进度返回200
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldGetProgressAsAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/wizard/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStep").value(0))
                .andExpect(jsonPath("$.data.completed").value(false));
    }

    // 功能：PM角色查询向导进度返回200
    @Test
    @WithMockUser(roles = "PM")
    void shouldGetProgressAsPm() throws Exception {
        mockMvc.perform(get("/api/v1/wizard/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStep").value(0));
    }

    // 功能：无角色用户查询进度返回403
    @Test
    @WithMockUser(roles = "EMPLOYEE")
    void shouldRejectGetProgressForWrongRole() throws Exception {
        mockMvc.perform(get("/api/v1/wizard/progress"))
                .andExpect(status().isForbidden());
    }

    // 功能：未认证用户查询进度返回401
    @Test
    void shouldRejectGetProgressUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/wizard/progress"))
                .andExpect(status().isUnauthorized());
    }

    // ========================================
    // 步骤1 — 创建项目角色
    // ========================================

    // 功能：ADMIN执行步骤1——创建项目角色并推进进度
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCompleteStep1CreateProjectRole() throws Exception {
        Map<String, String> body = Map.of(
                "roleCode", "TEST-" + unique(),
                "roleName", "测试角色");

        mockMvc.perform(post("/api/v1/wizard/step/project-role")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedStep").value(1))
                .andExpect(jsonPath("$.data.nextStep").value(2))
                .andExpect(jsonPath("$.data.wizardCompleted").value(false));
    }

    // 功能：缺少必填字段返回400
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectStep1MissingRequiredFields() throws Exception {
        Map<String, String> body = Map.of("roleCode", "");

        mockMvc.perform(post("/api/v1/wizard/step/project-role")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // 功能：PM角色无法执行步骤1（仅ADMIN可执行步骤）
    @Test
    @WithMockUser(roles = "PM")
    void shouldRejectStep1ForNonAdmin() throws Exception {
        Map<String, String> body = Map.of(
                "roleCode", "TEST-" + unique(),
                "roleName", "测试角色");

        mockMvc.perform(post("/api/v1/wizard/step/project-role")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    // ========================================
    // 步骤2 — 标记导入完成
    // ========================================

    // 功能：步骤2为无操作占位——仅推进进度
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCompleteStep2ImportEmployee() throws Exception {
        mockMvc.perform(post("/api/v1/wizard/step/import-employee")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedStep").value(2))
                .andExpect(jsonPath("$.data.nextStep").value(3));
    }

    // ========================================
    // 步骤3 — 创建项目
    // ========================================

    // 功能：ADMIN执行步骤3——创建项目
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCompleteStep3CreateProject() throws Exception {
        Map<String, String> body = Map.of(
                "projectCode", "PRJ-" + unique(),
                "projectName", "测试项目",
                "projectStage", "P2");

        mockMvc.perform(post("/api/v1/wizard/step/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedStep").value(3))
                .andExpect(jsonPath("$.data.nextStep").value(4));
    }

    // ========================================
    // 步骤4 — 分配项目角色人员
    // ========================================

    // 功能：ADMIN执行步骤4——分配员工到项目角色
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCompleteStep4RoleAssignment() throws Exception {
        String roleCode = "R4-" + unique();
        String projectCode = "PR4-" + unique();

        // 先创建项目角色和项目（步骤4需要它们存在）
        mockMvc.perform(post("/api/v1/wizard/step/project-role")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("roleCode", roleCode, "roleName", "测试角色"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/wizard/step/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("projectCode", projectCode, "projectName", "测试项目", "projectStage", "P3"))))
                .andExpect(status().isOk());

        Map<String, String> body = Map.of(
                "projectCode", projectCode,
                "projectStage", "P3",
                "roleCode", roleCode,
                "employeeId", "ADMIN");

        mockMvc.perform(post("/api/v1/wizard/step/role-assignment")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedStep").value(4))
                .andExpect(jsonPath("$.data.nextStep").value(5));
    }

    // ========================================
    // 步骤7 — 创建考核周期
    // ========================================

    // 功能：ADMIN执行步骤7——创建考核周期
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCompleteStep7CreatePeriod() throws Exception {
        Map<String, Object> body = Map.of(
                "periodName", "测试周期-" + unique(),
                "startDate", LocalDate.of(2026, 1, 1).toString(),
                "endDate", LocalDate.of(2026, 6, 30).toString());

        mockMvc.perform(post("/api/v1/wizard/step/period")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedStep").value(7));
    }

    // 功能：缺少必填字段返回400
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectStep7MissingRequiredFields() throws Exception {
        Map<String, Object> body = Map.of("periodName", "");

        mockMvc.perform(post("/api/v1/wizard/step/period")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // ========================================
    // 重置测试
    // ========================================

    // 功能：ADMIN重置向导返回200
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldResetWizardAsAdmin() throws Exception {
        mockMvc.perform(put("/api/v1/wizard/reset")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("向导已重置"));
    }

    // 功能：非ADMIN角色重置向导返回403
    @Test
    @WithMockUser(roles = "PM")
    void shouldRejectResetForNonAdmin() throws Exception {
        mockMvc.perform(put("/api/v1/wizard/reset")
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // ========================================
    // 渐进流程测试
    // ========================================

    // 功能：顺序完成1-3-7步后查询进度正确反映完成状态
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldTrackSequentialProgress() throws Exception {
        // 步骤1
        Map<String, String> step1 = Map.of("roleCode", "SQ-" + unique(), "roleName", "顺序测试角色");
        mockMvc.perform(post("/api/v1/wizard/step/project-role")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(step1)))
                .andExpect(status().isOk());

        // 步骤3
        Map<String, String> step3 = Map.of(
                "projectCode", "PRJS-" + unique(), "projectName", "顺序测试", "projectStage", "P2");
        mockMvc.perform(post("/api/v1/wizard/step/project")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(step3)))
                .andExpect(status().isOk());

        // 查询进度
        mockMvc.perform(get("/api/v1/wizard/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStep").value(4))
                .andExpect(jsonPath("$.data.completedSteps").value("1,3"));
    }

    // ========================================
    // 入参校验——边界值
    // ========================================

    // 功能：Step5Request weight为负数时返回400
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectStep5NegativeWeight() throws Exception {
        Map<String, Object> body = Map.of(
                "projectRoleCode", "ROLE",
                "projectStage", "P2",
                "kpiName", "测试指标",
                "weight", new BigDecimal("-1"));

        mockMvc.perform(post("/api/v1/wizard/step/kpi")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // 功能：Step6Request weight超出100时返回400
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectStep6WeightExceedsMax() throws Exception {
        Map<String, Object> body = Map.of(
                "category", "研发技术类",
                "position", "测试岗位",
                "isProjectBased", true,
                "projectWeight", new BigDecimal("150.00"),
                "funcWeight", new BigDecimal("50.00"));

        mockMvc.perform(post("/api/v1/wizard/step/position")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    // 功能：Step1 roleCode为空时返回400校验失败
    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectStep1BlankRoleCode() throws Exception {
        Map<String, String> body = Map.of("roleCode", "   ", "roleName", "测试");

        mockMvc.perform(post("/api/v1/wizard/step/project-role")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }
}
