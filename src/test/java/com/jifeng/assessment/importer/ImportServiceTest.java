// 模块用途：ImportService 单元测试——覆盖Excel预览、执行导入、校验、去重
// 依赖文件：ImportService.java, ImportResultDTO.java, Employee.java
// 修改注意：用POI在内存中构造Excel文件，不依赖外部文件，每个测试独立回滚
package com.jifeng.assessment.importer;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.positioncategory.PositionCategory;
import com.jifeng.assessment.positioncategory.PositionCategoryMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ImportServiceTest {

    @Autowired
    private ImportService importService;

    @Autowired
    private EmployeeMapper employeeMapper;

    @Autowired
    private PositionCategoryMapper positionCategoryMapper;

    @BeforeEach
    void seedCategories() {
        if (positionCategoryMapper.selectCount(null) == 0) {
            PositionCategory cat = new PositionCategory();
            cat.setName("研发技术类");
            cat.setSortOrder(10);
            positionCategoryMapper.insert(cat);
        }
    }

    // 辅助：生成包含表头+数据行的Excel文件字节数组
    private byte[] createExcel(String[] headers, String[][] data) throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < data.length; r++) {
                Row dataRow = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) {
                    dataRow.createCell(c).setCellValue(data[r][c] == null ? "" : data[r][c]);
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // 辅助：创建 MockMultipartFile
    private MockMultipartFile mockFile(String filename, String[] headers, String[][] data) throws Exception {
        return new MockMultipartFile("file", filename,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                createExcel(headers, data));
    }

    private String id() {
        return "IMP" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
    }

    // ========================================
    // 预览测试
    // ========================================

    // 功能：预览有效Excel——返回表头、前10行、总行数
    @Test
    void shouldPreviewValidExcel() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类", "部门"};
        String[][] data = {
                {id(), "张三", "zhangsan@test.com", "研发技术类", "研发部"},
                {id(), "李四", "lisi@test.com", "研发技术类", "质量部"},
        };
        MockMultipartFile file = mockFile("test.xlsx", headers, data);

        ImportResultDTO.PreviewResult result = importService.preview(file);
        assertEquals(5, result.getHeaders().size());
        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getSampleRows().size());
        assertTrue(result.getSampleRows().get(0).containsKey("name"));
        assertEquals("张三", result.getSampleRows().get(0).get("name"));
    }

    // 功能：预览超1000行Excel时拒绝
    @Test
    void shouldRejectPreviewExceedsMaxRows() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱"};
        String[][] data = new String[1001][3];
        for (int i = 0; i < 1001; i++) {
            data[i][0] = id();
            data[i][1] = "员工" + i;
            data[i][2] = "emp" + i + "@test.com";
        }
        MockMultipartFile file = mockFile("large.xlsx", headers, data);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.preview(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("1000"));
    }

    // 功能：缺少工号列时拒绝
    @Test
    void shouldRejectPreviewMissingEmployeeIdColumn() throws Exception {
        String[] headers = {"姓名", "邮箱", "部门"};
        String[][] data = {{"张三", "z@t.com", "研发部"}};
        MockMultipartFile file = mockFile("noid.xlsx", headers, data);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.preview(file));
        assertTrue(ex.getMessage().contains("工号"));
    }

    // ========================================
    // 执行导入测试
    // ========================================

    // 功能：执行导入——成功创建所有员工
    @Test
    void shouldExecuteImportSuccessfully() throws Exception {
        String empId1 = id();
        String empId2 = id();
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类", "部门"};
        String[][] data = {
                {empId1, "测试员A", "testa@jifeng.com", "研发技术类", "研发部"},
                {empId2, "测试员B", "testb@jifeng.com", "研发技术类", "质量部"},
        };
        MockMultipartFile file = mockFile("test.xlsx", headers, data);

        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertNull(result.getErrors());

        // 验证数据已写入
        assertNotNull(employeeMapper.selectById(empId1));
        assertEquals("测试员A", employeeMapper.selectById(empId1).getName());
        assertNotNull(employeeMapper.selectById(empId2));
    }

    // 功能：工号重复时计入错误
    @Test
    void shouldReportDuplicateEmployeeId() throws Exception {
        String dupId = id();
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {
                {dupId, "首次出现", "first@test.com", "研发技术类"},
                {dupId, "重复工号", "second@test.com", "研发技术类"},
        };
        MockMultipartFile file = mockFile("dup.xlsx", headers, data);

        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(2, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertNotNull(result.getErrors());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).getReason().contains("已存在"));
    }

    // 功能：缺少必填字段时计入错误
    @Test
    void shouldReportValidationErrors() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {
                {id(), "正常员工", "normal@test.com", "研发技术类"},
                {"", "缺工号", "", "研发技术类"},          // 工号为空
                {id(), "", "noemail@t.com", "研发技术类"}, // 姓名为空
        };
        MockMultipartFile file = mockFile("bad.xlsx", headers, data);

        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(3, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertEquals(2, result.getFailCount());
        assertEquals(2, result.getErrors().size());
    }

    // 功能：邮箱格式不正确时计入错误
    @Test
    void shouldRejectInvalidEmail() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {{id(), "无效邮箱员工", "not-an-email", "研发技术类"}};
        MockMultipartFile file = mockFile("bademail.xlsx", headers, data);

        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).getReason().contains("邮箱格式不正确"));
    }

    // 功能：不存在的直属上级工号计入错误
    @Test
    void shouldRejectNonexistentLeader() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类", "直属上级工号"};
        String[][] data = {{id(), "新员工", "new@test.com", "研发技术类", "NONEXISTENT"}};
        MockMultipartFile file = mockFile("badleader.xlsx", headers, data);

        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).getReason().contains("直属上级工号不存在"));
    }

    // 功能：Excel含空行时跳过，不影响有效行
    @Test
    void shouldSkipEmptyRows() throws Exception {
        String empId = id();
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {
                {empId, "有效员工", "valid@test.com", "研发技术类"},
                {null, null, null, null},
        };
        MockMultipartFile file = mockFile("withempty.xlsx", headers, data);

        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertNotNull(employeeMapper.selectById(empId));
    }

    // ========================================
    // 补充测试（审查新增）
    // ========================================

    // 功能：execute超1000行时拒绝
    @Test
    void shouldRejectExecuteExceedsMaxRows() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱"};
        String[][] data = new String[1001][3];
        for (int i = 0; i < 1001; i++) {
            data[i][0] = id();
            data[i][1] = "员工" + i;
            data[i][2] = "emp" + i + "@test.com";
        }
        MockMultipartFile file = mockFile("large.xlsx", headers, data);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.execute(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("1000"));
    }

    // 功能：恰好1000行边界值通过
    @Test
    void shouldAcceptExactlyMaxRows() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱"};
        String[][] data = new String[1000][3];
        for (int i = 0; i < 1000; i++) {
            data[i][0] = id();
            data[i][1] = "员工" + i;
            data[i][2] = "emp" + i + "@test.com";
        }
        MockMultipartFile file = mockFile("max.xlsx", headers, data);
        ImportResultDTO.PreviewResult result = importService.preview(file);
        assertEquals(1000, result.getTotalRows());
    }

    // 功能：非Excel文件解析失败
    @Test
    void shouldRejectNonExcelFileInPreview() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "this is not an excel file".getBytes());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> importService.preview(file));
        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("文件解析失败"));
    }

    // 功能：工号格式无效（太短/含特殊字符）
    @Test
    void shouldReportInvalidEmployeeIdFormat() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {
                {"AB", "太短工号", "short@test.com", "研发技术类"},
                {"E@001", "特殊字符", "special@test.com", "研发技术类"},
        };
        MockMultipartFile file = mockFile("badid.xlsx", headers, data);
        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(2, result.getFailCount());
        assertTrue(result.getErrors().stream()
                .anyMatch(e -> e.getReason().contains("工号格式不正确")));
    }

    // 功能：状态值无效时计入错误
    @Test
    void shouldRejectInvalidStatus() throws Exception {
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类", "状态"};
        String[][] data = {{id(), "测试", "test@test.com", "研发技术类", "DELETED"}};
        MockMultipartFile file = mockFile("badstatus.xlsx", headers, data);
        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(1, result.getFailCount());
        assertTrue(result.getErrors().get(0).getReason().contains("员工状态无效"));
    }

    // 功能：缺少category列时拒绝——category已从自由文本改为受控字典，必须存在
    @Test
    void shouldRejectMissingCategory() throws Exception {
        String empId = id();
        String[] headers = {"工号", "姓名", "邮箱"};
        String[][] data = {{empId, "默认值测试", "default@test.com"}};
        MockMultipartFile file = mockFile("minimal.xlsx", headers, data);
        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(1, result.getFailCount());
        assertNotNull(result.getErrors());
        assertTrue(result.getErrors().get(0).getReason().contains("岗位分类不能为空"));
    }

    // 功能：无status列时默认为ACTIVE
    @Test
    void shouldDefaultStatusToActive() throws Exception {
        String empId = id();
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {{empId, "默认状态", "status@test.com", "研发技术类"}};
        MockMultipartFile file = mockFile("nostatus.xlsx", headers, data);
        importService.execute(file);
        assertEquals("ACTIVE", employeeMapper.selectById(empId).getStatus());
    }

    // 功能：大小写不敏感英文表头映射
    @Test
    void shouldMapCaseInsensitiveEnglishHeaders() throws Exception {
        String empId = id();
        String[] headers = {"EMPLOYEE_ID", "NAME", "EMAIL"};
        String[][] data = {{empId, "CaseTest", "case@test.com"}};
        MockMultipartFile file = mockFile("case.xlsx", headers, data);
        ImportResultDTO.PreviewResult result = importService.preview(file);
        assertTrue(result.getSampleRows().get(0).containsKey("employeeId"));
        assertTrue(result.getSampleRows().get(0).containsKey("name"));
    }

    // 功能：NUMERIC单元格类型自动转字符串（整数无小数点）
    @Test
    void shouldHandleNumericCells() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("工号");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("邮箱");
            header.createCell(3).setCellValue("岗位分类");
            Row data = sheet.createRow(1);
            data.createCell(0).setCellValue(1001.0);
            data.createCell(1).setCellValue("数字工号");
            data.createCell(2).setCellValue("num@test.com");
            data.createCell(3).setCellValue("研发技术类");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            MockMultipartFile file = new MockMultipartFile("file", "num.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    bos.toByteArray());
            ImportResultDTO.ExecuteResult result = importService.execute(file);
            assertEquals(1, result.getSuccessCount());
            assertEquals("1001", employeeMapper.selectById("1001").getEmployeeId());
        }
    }

    // 功能：execute空行不计入totalRows
    @Test
    void shouldCountOnlyDataRowsInExecuteResult() throws Exception {
        String empId = id();
        String[] headers = {"工号", "姓名", "邮箱", "岗位分类"};
        String[][] data = {
                {empId, "唯一数据", "only@test.com", "研发技术类"},
                {null, null, null, null},
                {null, null, null, null},
        };
        MockMultipartFile file = mockFile("count.xlsx", headers, data);
        ImportResultDTO.ExecuteResult result = importService.execute(file);
        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
        assertNotNull(employeeMapper.selectById(empId));
    }
}
