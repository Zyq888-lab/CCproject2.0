// 模块用途：岗位分类 DTO——前端下拉选项和列表展示
// 依赖文件：无
// 修改注意：仅暴露 id/name/sortOrder/createdAt，不泄露 deleted/version 等内部字段
package com.jifeng.assessment.positioncategory;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PositionCategoryDTO {

    private Long id;
    private String name;
    private Integer sortOrder;
    private LocalDateTime createdAt;
}
