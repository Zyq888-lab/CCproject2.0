// 模块用途：导入结果DTO——预览结果 + 导入执行报告（成功/失败/错误明细）
// 依赖文件：无
// 修改注意：errors 为 null 时前端显示"全部成功"，不要返回空数组
package com.jifeng.assessment.importer;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ImportResultDTO {

    // ======== 预览 ========

    @Data
    @AllArgsConstructor
    public static class PreviewResult {
        private List<String> headers;
        private List<Map<String, String>> sampleRows;
        private int totalRows;
    }

    // ======== 执行 ========

    @Data
    public static class ExecuteResult {
        private int totalRows;
        private int successCount;
        private int failCount;
        private List<ImportError> errors;

        public static ExecuteResult success(int total, int successCount) {
            ExecuteResult r = new ExecuteResult();
            r.totalRows = total;
            r.successCount = successCount;
            r.failCount = 0;
            return r;
        }
    }

    @Data
    @AllArgsConstructor
    public static class ImportError {
        private int row;
        private String employeeId;
        private String reason;
    }
}
