// 模块用途：项目参与录入项——员工填写参与时的单条项目记录
// 依赖文件：无
// 修改注意：participationRate 为百分制(1-100)，前端传整数，后端存储 DECIMAL(5,2)
package com.jifeng.assessment.participation;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProjectParticipationItem {

    private String projectCode;
    private String projectStage;
    private BigDecimal participationRate;
}
