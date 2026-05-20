// 模块用途：向导进度返回对象——前端通过此对象判断当前步骤和是否完成
// 依赖文件：WizardProgress.java
package com.jifeng.assessment.wizard;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class WizardProgressDTO {

    private int currentStep;
    private String completedSteps;
    private boolean completed;

    public static WizardProgressDTO neverStarted() {
        return new WizardProgressDTO(0, "", false);
    }
}
