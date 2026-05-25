// 模块用途：配置向导业务逻辑——进度查询、标记步骤完成、重置向导
// 依赖文件：WizardProgressMapper.java, WizardProgress.java, WizardProgressDTO.java
// 修改注意：completed_steps 逗号分隔，去重；reset 使用逻辑删除
package com.jifeng.assessment.wizard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WizardService {

    private final WizardProgressMapper wizardProgressMapper;

    private static final int TOTAL_STEPS = 7;

    // 功能：查询当前用户的向导进度——首次使用返回 currentStep=0
    public WizardProgressDTO getProgress(String userId) {
        WizardProgress progress = find(userId);
        if (progress == null) {
            return WizardProgressDTO.neverStarted();
        }
        return toDTO(progress);
    }

    // 功能：标记某一步为已完成——自动推进 currentStep，去重
    @Transactional
    public WizardProgressDTO completeStep(int step, String userId) {
        if (step < 1 || step > TOTAL_STEPS) {
            throw new IllegalArgumentException("无效步骤: " + step + "，有效范围 1-" + TOTAL_STEPS);
        }
        WizardProgress progress = getOrCreate(userId);

        Set<String> completed = parseCompletedSteps(progress.getCompletedSteps());
        completed.add(String.valueOf(step));

        int nextStep = step < TOTAL_STEPS ? step + 1 : step;
        progress.setCurrentStep(nextStep);
        progress.setCompletedSteps(String.join(",", completed.stream().sorted().toList()));
        progress.setUpdatedAt(LocalDateTime.now());
        wizardProgressMapper.updateById(progress);

        return toDTO(progress);
    }

    // 功能：重置向导进度——将当前进度恢复到步骤1，避免软删除导致的唯一约束冲突
    @Transactional
    public void reset(String userId) {
        WizardProgress progress = find(userId);
        if (progress != null) {
            progress.setCurrentStep(1);
            progress.setCompletedSteps("");
            progress.setUpdatedAt(LocalDateTime.now());
            wizardProgressMapper.updateById(progress);
        }
    }

    // 辅助：查找当前用户的进度记录
    private WizardProgress find(String userId) {
        return wizardProgressMapper.selectOne(
                new LambdaQueryWrapper<WizardProgress>().eq(WizardProgress::getUserId, userId));
    }

    // 辅助：获取或创建进度记录
    private WizardProgress getOrCreate(String userId) {
        WizardProgress progress = find(userId);
        if (progress == null) {
            progress = new WizardProgress();
            progress.setUserId(userId);
            progress.setCurrentStep(1);
            progress.setCompletedSteps("");
            wizardProgressMapper.insert(progress);
        }
        return progress;
    }

    // 辅助：解析 completed_steps 字符串为集合
    private Set<String> parseCompletedSteps(String completedSteps) {
        if (completedSteps == null || completedSteps.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(completedSteps.split(",")));
    }

    // 辅助：实体转DTO
    private WizardProgressDTO toDTO(WizardProgress progress) {
        int current = progress.getCurrentStep();
        Set<String> completed = parseCompletedSteps(progress.getCompletedSteps());
        boolean allDone = completed.size() >= TOTAL_STEPS;
        return new WizardProgressDTO(current, progress.getCompletedSteps(), allDone);
    }
}
