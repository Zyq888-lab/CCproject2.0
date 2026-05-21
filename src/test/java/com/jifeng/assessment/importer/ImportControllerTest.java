// 模块用途：ImportController 集成测试——覆盖权限校验、文件验证
// 依赖文件：ImportController.java, ImportService.java, SecurityConfig.java
// 修改注意：POST需携带CSRF token，@WithMockUser模拟角色，每个测试独立回滚
package com.jifeng.assessment.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private MockMultipartFile validXlsx() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("工号");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("邮箱");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue("E001");
            data.createCell(1).setCellValue("测试");
            data.createCell(2).setCellValue("test@test.com");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return new MockMultipartFile("file", "test.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bos.toByteArray());
        }
    }

    // ======== preview 权限测试 ========

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldAcceptPreviewAsAdmin() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/preview")
                        .file(validXlsx()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldRejectPreviewWhenNotAdmin() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/preview")
                        .file(validXlsx()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectPreviewWhenUnauthenticated() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/preview")
                        .file(validXlsx()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectPreviewWithEmptyFile() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[0]);
        mockMvc.perform(multipart("/api/v1/import/employees/preview")
                        .file(empty).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectPreviewWithoutCsrf() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/preview")
                        .file(validXlsx()))
                .andExpect(status().isForbidden());
    }

    // ======== execute 权限测试 ========

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldAcceptExecuteAsAdmin() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/execute")
                        .file(validXlsx()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldRejectExecuteWhenNotAdmin() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/execute")
                        .file(validXlsx()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectExecuteWhenUnauthenticated() throws Exception {
        mockMvc.perform(multipart("/api/v1/import/employees/execute")
                        .file(validXlsx()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectExecuteWithEmptyFile() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "empty.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                new byte[0]);
        mockMvc.perform(multipart("/api/v1/import/employees/execute")
                        .file(empty).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
