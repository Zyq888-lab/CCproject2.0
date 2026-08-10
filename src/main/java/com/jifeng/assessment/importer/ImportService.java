// 模块用途：导入业务逻辑——Excel解析、字段映射、逐行校验、批量创建员工
// 依赖文件：EmployeeMapper.java, Employee.java, EmployeeValidator.java, ImportResultDTO.java
// 修改注意：校验规则与 EmployeeService.validateOnCreate 保持一致，新增字段需同步更新 FIELD_MAPPING
package com.jifeng.assessment.importer;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.employee.EmployeeValidator;
import com.jifeng.assessment.positioncategory.PositionCategory;
import com.jifeng.assessment.positioncategory.PositionCategoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EmptyFileException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final EmployeeMapper employeeMapper;
    private final PositionCategoryMapper positionCategoryMapper;

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
            ParsedSheet parsed = parseSheet(workbook.getSheetAt(0));

            List<Map<String, String>> sampleRows = new ArrayList<>();
            int sampleSize = Math.min(PREVIEW_ROWS, parsed.rawRows);
            for (int i = 0; i < sampleSize; i++) {
                Row row = parsed.sheet.getRow(i + 1);
                if (row == null) continue;
                Map<String, String> rowData = readDataRow(row, parsed.columnMapping);
                if (!rowData.isEmpty()) {
                    sampleRows.add(rowData);
                }
            }

            return new ImportResultDTO.PreviewResult(parsed.headers, sampleRows, parsed.rawRows);
        } catch (BusinessException e) {
            throw e;
        } catch (EmptyFileException e) {
            throw new BusinessException(400, "文件为空，请检查文件内容");
        } catch (EncryptedDocumentException e) {
            throw new BusinessException(400, "文件已加密或设置了密码，请解密后重新上传");
        } catch (Exception e) {
            log.error("预览文件解析失败", e);
            throw new BusinessException(400, "文件解析失败，请检查文件格式（仅支持 .xlsx / .xls）");
        }
    }

    // 功能：执行导入——批量预取已有工号和上级工号，逐行校验后插入，返回导入报告
    @Transactional
    public ImportResultDTO.ExecuteResult execute(MultipartFile file) {
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {
            ParsedSheet parsed = parseSheet(workbook.getSheetAt(0));

            // 第一遍扫描：收集所有工号、直属上级工号、邮箱
            Set<String> allEmployeeIds = new HashSet<>();
            Set<String> allLeaderIds = new HashSet<>();
            Set<String> allEmails = new HashSet<>();
            Set<String> allCategories = new HashSet<>();
            int empIdCol = getColumnIndex(parsed.columnMapping, "employeeId");
            int leaderCol = getColumnIndex(parsed.columnMapping, "directLeaderId");
            int emailCol = getColumnIndex(parsed.columnMapping, "email");
            int catCol = getColumnIndex(parsed.columnMapping, "category");
            for (int i = 0; i < parsed.rawRows; i++) {
                Row row = parsed.sheet.getRow(i + 1);
                if (row == null || isRowEmpty(row)) continue;
                String empId = readCell(row.getCell(empIdCol));
                if (!empId.isEmpty()) allEmployeeIds.add(empId);
                if (leaderCol >= 0) {
                    String leaderId = readCell(row.getCell(leaderCol));
                    if (!leaderId.isEmpty()) allLeaderIds.add(leaderId);
                }
                if (emailCol >= 0) {
                    String email = readCell(row.getCell(emailCol));
                    if (!email.isEmpty()) allEmails.add(email);
                }
                if (catCol >= 0) {
                    String cat = readCell(row.getCell(catCol));
                    if (!cat.isEmpty()) allCategories.add(cat);
                }
            }

            // 批量查询已有员工（内存set替代N次selectById）
            Set<String> existingIds = allEmployeeIds.isEmpty() ? Set.of() :
                    employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                                    .in(Employee::getEmployeeId, allEmployeeIds))
                            .stream().map(Employee::getEmployeeId).collect(Collectors.toSet());

            // 批量查询已有上级
            Set<String> existingLeaders = allLeaderIds.isEmpty() ? Set.of() :
                    employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                                    .in(Employee::getEmployeeId, allLeaderIds))
                            .stream().map(Employee::getEmployeeId).collect(Collectors.toSet());

            // 批量查询已有邮箱（用于唯一性校验）
            Set<String> existingEmails = allEmails.isEmpty() ? Set.of() :
                    employeeMapper.selectList(new LambdaQueryWrapper<Employee>()
                                    .in(Employee::getEmail, allEmails))
                            .stream().map(Employee::getEmail).collect(Collectors.toSet());

            // 批量查询有效岗位分类
            Set<String> validCategories = allCategories.isEmpty() ? Set.of() :
                    positionCategoryMapper.selectList(new LambdaQueryWrapper<PositionCategory>()
                                    .eq(PositionCategory::getDeleted, 0)
                                    .in(PositionCategory::getName, allCategories))
                            .stream().map(PositionCategory::getName).collect(Collectors.toSet());

            int successCount = 0;
            int dataRowCount = 0;
            Set<String> batchEmails = new HashSet<>(); // 防同一批次内重复邮箱
            List<ImportResultDTO.ImportError> errors = new ArrayList<>();

            for (int i = 0; i < parsed.rawRows; i++) {
                Row row = parsed.sheet.getRow(i + 1);
                int rowNum = i + 2; // 1-based, 表头行=1

                if (row == null || isRowEmpty(row)) continue;
                dataRowCount++;

                try {
                    Employee emp = buildEmployee(row, rowNum, parsed.columnMapping,
                            existingIds, existingLeaders, existingEmails, batchEmails, validCategories);
                    employeeMapper.insert(emp);
                    successCount++;
                    if (StringUtils.hasText(emp.getEmail())) {
                        batchEmails.add(emp.getEmail());
                    }
                } catch (BusinessException e) {
                    errors.add(new ImportResultDTO.ImportError(
                            rowNum, readCell(row.getCell(0)), e.getMessage()));
                } catch (DuplicateKeyException e) {
                    String empId = readCell(row.getCell(0));
                    log.warn("Row {}: duplicate employeeId {}", rowNum, empId, e);
                    errors.add(new ImportResultDTO.ImportError(
                            rowNum, empId, "工号" + empId + "已存在"));
                } catch (DataAccessException e) {
                    String empId = readCell(row.getCell(0));
                    log.error("Row {} data access error", rowNum, e);
                    errors.add(new ImportResultDTO.ImportError(
                            rowNum, empId, "数据写入失败: " + e.getMostSpecificCause().getMessage()));
                } catch (Exception e) {
                    String empId = readCell(row.getCell(0));
                    log.error("Row {} import failed", rowNum, e);
                    errors.add(new ImportResultDTO.ImportError(
                            rowNum, empId, "系统错误，请联系管理员"));
                }
            }

            ImportResultDTO.ExecuteResult result = new ImportResultDTO.ExecuteResult();
            result.setTotalRows(dataRowCount);
            result.setSuccessCount(successCount);
            result.setFailCount(errors.size());
            if (!errors.isEmpty()) {
                result.setErrors(errors);
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (EmptyFileException e) {
            throw new BusinessException(400, "文件为空，请检查文件内容");
        } catch (EncryptedDocumentException e) {
            throw new BusinessException(400, "文件已加密或设置了密码，请解密后重新上传");
        } catch (Exception e) {
            log.error("执行导入文件解析失败", e);
            throw new BusinessException(400, "文件解析失败，请检查文件格式（仅支持 .xlsx / .xls）");
        }
    }

    // ========================================
    // 辅助方法
    // ========================================

    // 解析结果记录——存放parseSheet的输出
    private record ParsedSheet(Sheet sheet, List<String> headers,
                               Map<Integer, String> columnMapping, int rawRows) {}

    // 辅助：解析Sheet——空文件检查、表头读取、列映射构建、行数上限检查
    private ParsedSheet parseSheet(Sheet sheet) {
        if (sheet.getLastRowNum() < 1) {
            throw new BusinessException(400, "文件为空或仅包含表头");
        }
        List<String> headers = readHeaders(sheet);
        Map<Integer, String> columnMapping = buildColumnMapping(headers);
        int rawRows = sheet.getLastRowNum();
        if (rawRows > MAX_ROWS) {
            throw new BusinessException(400, "单次导入不能超过" + MAX_ROWS + "行，当前: " + rawRows);
        }
        return new ParsedSheet(sheet, headers, columnMapping, rawRows);
    }

    // 辅助：根据字段名反查列索引
    private int getColumnIndex(Map<Integer, String> columnMapping, String fieldName) {
        for (Map.Entry<Integer, String> entry : columnMapping.entrySet()) {
            if (fieldName.equals(entry.getValue())) return entry.getKey();
        }
        return -1;
    }

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
            } else {
                String lowerRaw = raw.toLowerCase();
                if (FIELD_MAPPING.containsKey(lowerRaw)) {
                    mapping.put(i, FIELD_MAPPING.get(lowerRaw));
                    mappedFields.add(FIELD_MAPPING.get(lowerRaw));
                }
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

    // 辅助：根据列映射构建Employee对象并校验（使用预取的Set替代selectById）
    private Employee buildEmployee(Row row, int rowNum, Map<Integer, String> columnMapping,
                                   Set<String> existingEmployeeIds, Set<String> existingLeaderIds,
                                   Set<String> existingEmails, Set<String> batchEmails,
                                   Set<String> validCategories) {
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
            throw new BusinessException(400, "第" + rowNum + "行：岗位分类不能为空");
        }
        if (!validCategories.contains(emp.getCategory())) {
            throw new BusinessException(400, "第" + rowNum + "行：岗位分类 '" + emp.getCategory() + "' 不存在，请先在岗位分类管理中维护");
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
            emp.setStatus(EmployeeValidator.mapStatus(emp.getStatus()));
        } else {
            emp.setStatus("ACTIVE");
        }

        // 直属上级存在校验（内存Set查找替代selectById）
        if (StringUtils.hasText(emp.getDirectLeaderId())) {
            if (!existingLeaderIds.contains(emp.getDirectLeaderId())) {
                throw new BusinessException(400, "第" + rowNum + "行：直属上级工号不存在: " + emp.getDirectLeaderId());
            }
        }

        // 工号唯一校验（内存Set查找替代selectById）
        if (existingEmployeeIds.contains(emp.getEmployeeId())) {
            throw new BusinessException(409, "第" + rowNum + "行：工号" + emp.getEmployeeId() + "已存在");
        }

        // 邮箱唯一校验（内存Set查找）
        if (StringUtils.hasText(emp.getEmail())) {
            if (existingEmails.contains(emp.getEmail())) {
                throw new BusinessException(409, "第" + rowNum + "行：邮箱" + emp.getEmail() + "已存在");
            }
            if (batchEmails.contains(emp.getEmail())) {
                throw new BusinessException(409, "第" + rowNum + "行：本批次内邮箱重复: " + emp.getEmail());
            }
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
