// 模块用途：导入业务逻辑——Excel解析、字段映射、逐行校验、批量创建员工
// 依赖文件：EmployeeService.java, Employee.java, EmployeeValidator.java, ImportResultDTO.java
// 修改注意：校验规则与 EmployeeService.validateOnCreate 保持一致，新增字段需同步更新 FIELD_MAPPING
package com.jifeng.assessment.importer;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.employee.EmployeeValidator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;

@Service
@RequiredArgsConstructor
public class ImportService {

    private final EmployeeMapper employeeMapper;

    // 最大预览行数
    private static final int PREVIEW_ROWS = 10;
    // 单次导入最大行数
    private static final int MAX_ROWS = 1000;

    // 功能：Excel表头到Employee字段的映射——支持中文、英文、下划线多种写法
    private static final Map<String, String> FIELD_MAPPING = new LinkedHashMap<>();
    static {
        // employeeId
        FIELD_MAPPING.put("工号", "employeeId");
        FIELD_MAPPING.put("员工编号", "employeeId");
        FIELD_MAPPING.put("employee_id", "employeeId");
        FIELD_MAPPING.put("employee id", "employeeId");
        // name
        FIELD_MAPPING.put("姓名", "name");
        FIELD_MAPPING.put("员工姓名", "name");
        FIELD_MAPPING.put("name", "name");
        // email
        FIELD_MAPPING.put("邮箱", "email");
        FIELD_MAPPING.put("电子邮箱", "email");
        FIELD_MAPPING.put("email", "email");
        FIELD_MAPPING.put("邮件", "email");
        // category
        FIELD_MAPPING.put("岗位分类", "category");
        FIELD_MAPPING.put("category", "category");
        FIELD_MAPPING.put("类别", "category");
        // position
        FIELD_MAPPING.put("岗位", "position");
        FIELD_MAPPING.put("岗位名称", "position");
        FIELD_MAPPING.put("position", "position");
        // orgName
        FIELD_MAPPING.put("部门", "orgName");
        FIELD_MAPPING.put("组织", "orgName");
        FIELD_MAPPING.put("org_name", "orgName");
        FIELD_MAPPING.put("org", "orgName");
        // directLeaderId
        FIELD_MAPPING.put("直属上级工号", "directLeaderId");
        FIELD_MAPPING.put("上级工号", "directLeaderId");
        FIELD_MAPPING.put("direct_leader_id", "directLeaderId");
        FIELD_MAPPING.put("leader", "directLeaderId");
        // status
        FIELD_MAPPING.put("状态", "status");
        FIELD_MAPPING.put("status", "status");
    }

    // Employee字段名 → setter方法引用
    private static final Map<String, BiConsumer<Employee, String>> SETTERS = Map.of(
            "employeeId", (e, v) -> e.setEmployeeId(v),
            "name", (e, v) -> e.setName(v),
            "email", (e, v) -> e.setEmail(v),
            "category", (e, v) -> e.setCategory(v),
            "position", (e, v) -> e.setPosition(v),
            "orgName", (e, v) -> e.setOrgName(v),
            "directLeaderId", (e, v) -> e.setDirectLeaderId(v),
            "status", (e, v) -> e.setStatus(v)
    );

    // 功能：预览Excel——读取表头、前10行数据、总行数，不写入数据库
    public ImportResultDTO.PreviewResult preview(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) {
                throw new BusinessException(400, "文件为空或仅包含表头");
            }

            List<String> headers = readHeaders(sheet);
            Map<Integer, String> columnMapping = buildColumnMapping(headers);

            int totalRows = sheet.getLastRowNum(); // 不含表头
            if (totalRows > MAX_ROWS) {
                throw new BusinessException(400, "单次导入不能超过" + MAX_ROWS + "行，当前: " + totalRows);
            }

            List<Map<String, String>> sampleRows = new ArrayList<>();
            int sampleSize = Math.min(PREVIEW_ROWS, totalRows);
            for (int i = 0; i < sampleSize; i++) {
                Row row = sheet.getRow(i + 1);
                if (row == null) continue;
                Map<String, String> rowData = readDataRow(row, columnMapping);
                if (!rowData.isEmpty()) {
                    sampleRows.add(rowData);
                }
            }

            return new ImportResultDTO.PreviewResult(headers, sampleRows, totalRows);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "文件解析失败: " + e.getMessage());
        }
    }

    // 功能：执行导入——解析Excel全部数据行，逐行校验后创建员工，返回导入报告
    @Transactional
    public ImportResultDTO.ExecuteResult execute(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() < 1) {
                throw new BusinessException(400, "文件为空或仅包含表头");
            }

            List<String> headers = readHeaders(sheet);
            Map<Integer, String> columnMapping = buildColumnMapping(headers);

            int totalRows = sheet.getLastRowNum();
            if (totalRows > MAX_ROWS) {
                throw new BusinessException(400, "单次导入不能超过" + MAX_ROWS + "行，当前: " + totalRows);
            }

            int successCount = 0;
            List<ImportResultDTO.ImportError> errors = new ArrayList<>();

            for (int i = 0; i < totalRows; i++) {
                Row row = sheet.getRow(i + 1);
                int rowNum = i + 2; // 1-based, 表头行=1

                if (row == null || isRowEmpty(row)) continue;

                try {
                    Employee emp = buildEmployee(row, rowNum, columnMapping);
                    employeeMapper.insert(emp);
                    successCount++;
                } catch (BusinessException e) {
                    errors.add(new ImportResultDTO.ImportError(
                            rowNum, readCell(row.getCell(0)), e.getMessage()));
                } catch (Exception e) {
                    String empId = readCell(row.getCell(0));
                    if (e.getMessage() != null && e.getMessage().contains("duplicate")) {
                        errors.add(new ImportResultDTO.ImportError(
                                rowNum, empId, "工号" + empId + "已存在"));
                    } else {
                        errors.add(new ImportResultDTO.ImportError(
                                rowNum, empId, "系统错误: " + e.getMessage()));
                    }
                }
            }

            ImportResultDTO.ExecuteResult result = new ImportResultDTO.ExecuteResult();
            result.setTotalRows(totalRows);
            result.setSuccessCount(successCount);
            result.setFailCount(errors.size());
            if (!errors.isEmpty()) {
                result.setErrors(errors);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "文件解析失败: " + e.getMessage());
        }
    }

    // ========================================
    // 辅助方法
    // ========================================

    // 辅助：读取表头行，返回列名列表
    private List<String> readHeaders(Sheet sheet) {
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new BusinessException(400, "缺少表头行");
        }
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerRow.getLastCellNum(); i++) {
            headers.add(readCell(headerRow.getCell(i)));
        }
        return headers;
    }

    // 辅助：构建列索引→Employee字段名的映射
    private Map<Integer, String> buildColumnMapping(List<String> headers) {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        Set<String> mappedFields = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String raw = headers.get(i).trim();
            String field = FIELD_MAPPING.get(raw);
            if (field != null) {
                mapping.put(i, field);
                mappedFields.add(field);
            } else if (FIELD_MAPPING.containsKey(raw.toLowerCase())) {
                // 大小写不敏感fallback
                mapping.put(i, FIELD_MAPPING.get(raw.toLowerCase()));
                mappedFields.add(FIELD_MAPPING.get(raw.toLowerCase()));
            }
        }
        // 必须包含工号/employeeId 列
        if (!mappedFields.contains("employeeId")) {
            throw new BusinessException(400, "未找到工号列，支持的列名：工号、employee_id、员工编号");
        }
        return mapping;
    }

    // 辅助：读取单个单元格的字符串值（去除首位空格）
    private String readCell(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue().trim();
                } catch (Exception e) {
                    yield String.valueOf(cell.getNumericCellValue());
                }
            }
            default -> "";
        };
    }

    // 辅助：读取一行数据为 Map<String, String>
    private Map<String, String> readDataRow(Row row, Map<Integer, String> columnMapping) {
        Map<String, String> rowData = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : columnMapping.entrySet()) {
            String value = readCell(row.getCell(entry.getKey()));
            if (!value.isEmpty()) {
                rowData.put(entry.getValue(), value);
            }
        }
        return rowData;
    }

    // 辅助：根据列映射构建Employee对象并校验
    private Employee buildEmployee(Row row, int rowNum, Map<Integer, String> columnMapping) {
        Employee emp = new Employee();
        for (Map.Entry<Integer, String> entry : columnMapping.entrySet()) {
            String value = readCell(row.getCell(entry.getKey()));
            BiConsumer<Employee, String> setter = SETTERS.get(entry.getValue());
            if (setter != null && !value.isEmpty()) {
                setter.accept(emp, value);
            }
        }

        // 数据库NOT NULL字段默认值
        if (!StringUtils.hasText(emp.getCategory())) {
            emp.setCategory("未分类");
        }
        if (!StringUtils.hasText(emp.getPosition())) {
            emp.setPosition("未定义");
        }
        if (!StringUtils.hasText(emp.getOrgName())) {
            emp.setOrgName("未分配");
        }

        // 必填校验
        if (!StringUtils.hasText(emp.getEmployeeId())) {
            throw new BusinessException(400, "第" + rowNum + "行：工号不能为空");
        }
        if (!StringUtils.hasText(emp.getName())) {
            throw new BusinessException(400, "第" + rowNum + "行：姓名不能为空");
        }
        if (!StringUtils.hasText(emp.getEmail())) {
            throw new BusinessException(400, "第" + rowNum + "行：邮箱不能为空");
        }

        // 格式校验
        EmployeeValidator.validateEmployeeId(emp.getEmployeeId());
        EmployeeValidator.validateEmail(emp.getEmail());
        if (StringUtils.hasText(emp.getStatus())) {
            EmployeeValidator.validateStatus(emp.getStatus());
        } else {
            emp.setStatus("ACTIVE");
        }

        // 直属上级存在校验
        if (StringUtils.hasText(emp.getDirectLeaderId())) {
            Employee leader = employeeMapper.selectById(emp.getDirectLeaderId());
            if (leader == null) {
                throw new BusinessException(400, "第" + rowNum + "行：直属上级工号不存在: " + emp.getDirectLeaderId());
            }
        }

        // 工号唯一校验
        if (employeeMapper.selectById(emp.getEmployeeId()) != null) {
            throw new BusinessException(409, "第" + rowNum + "行：工号" + emp.getEmployeeId() + "已存在");
        }

        return emp;
    }

    // 辅助：判断一行是否全部为空
    private boolean isRowEmpty(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!readCell(row.getCell(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
