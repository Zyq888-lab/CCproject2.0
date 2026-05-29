// 模块用途：岗位分类业务逻辑——CRUD、名称唯一校验、引用检查防误删
// 依赖文件：PositionCategoryMapper.java, EmployeeMapper.java, PositionConfigMapper.java, FuncKpiMapper.java, BaseService.java
// 修改注意：deleteCategory 检查3张业务表引用，有任意引用则抛 409 拒绝删除
package com.jifeng.assessment.positioncategory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jifeng.assessment.common.BaseService;
import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.employee.Employee;
import com.jifeng.assessment.employee.EmployeeMapper;
import com.jifeng.assessment.kpi.FuncKpiConfig;
import com.jifeng.assessment.kpi.FuncKpiMapper;
import com.jifeng.assessment.position.PositionAssessmentConfig;
import com.jifeng.assessment.position.PositionConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PositionCategoryService extends BaseService<PositionCategoryMapper, PositionCategory> {

    private final EmployeeMapper employeeMapper;
    private final PositionConfigMapper positionConfigMapper;
    private final FuncKpiMapper funcKpiMapper;

    // 功能：查询所有未删除的分类，按 sort_order 升序 → id 升序
    public List<PositionCategory> listOrdered() {
        LambdaQueryWrapper<PositionCategory> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(PositionCategory::getSortOrder)
                .orderByAsc(PositionCategory::getId);
        return baseMapper.selectList(wrapper);
    }

    // 功能：名称唯一校验（含已逻辑删除记录）——excludeId 用于更新时排除自身
    public void checkNameUnique(String name, Long excludeId) {
        List<PositionCategory> all = baseMapper.findByNameIgnoreDeleted(name);
        for (PositionCategory pc : all) {
            if (!pc.getId().equals(excludeId)) {
                throw new BusinessException(400, "岗位分类名称已存在: " + name);
            }
        }
    }

    // 功能：创建分类——校验名称唯一，设置时间戳
    @Transactional
    public PositionCategory create(String name, Integer sortOrder) {
        checkNameUnique(name, null);
        PositionCategory entity = new PositionCategory();
        entity.setName(name);
        entity.setSortOrder(sortOrder != null ? sortOrder : 0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        try {
            baseMapper.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(409, "岗位分类名称已存在: " + name);
        }
        return entity;
    }

    // 功能：更新分类——乐观锁防并发覆盖，校验名称唯一
    @Transactional
    public PositionCategory update(Long id, String name, Integer sortOrder) {
        PositionCategory existing = baseMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "岗位分类不存在: " + id);
        }
        checkNameUnique(name, id);
        existing.setName(name);
        if (sortOrder != null) {
            existing.setSortOrder(sortOrder);
        }
        existing.setUpdatedAt(LocalDateTime.now());
        updateWithOptimisticLock(existing);
        return baseMapper.selectById(id);
    }

    // 功能：删除分类——检查3张业务表的 category 字段引用，有引用则拒绝删除
    @Transactional
    public void deleteCategory(Long id) {
        PositionCategory existing = baseMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "岗位分类不存在: " + id);
        }

        String categoryName = existing.getName();

        // 检查 employee.category 引用
        LambdaQueryWrapper<Employee> empWrapper = new LambdaQueryWrapper<>();
        empWrapper.eq(Employee::getCategory, categoryName)
                .eq(Employee::getDeleted, 0);
        if (employeeMapper.selectCount(empWrapper) > 0) {
            throw new BusinessException(409, "该分类被员工引用，无法删除");
        }

        // 检查 position_assessment_config.category 引用（@TableLogic 自动过滤 deleted=0）
        LambdaQueryWrapper<PositionAssessmentConfig> posWrapper = new LambdaQueryWrapper<>();
        posWrapper.eq(PositionAssessmentConfig::getCategory, categoryName);
        if (positionConfigMapper.selectCount(posWrapper) > 0) {
            throw new BusinessException(409, "该分类被岗位考核配置引用，无法删除");
        }

        // 检查 func_kpi_config.category 引用（@TableLogic 自动过滤 deleted=0）
        LambdaQueryWrapper<FuncKpiConfig> funcWrapper = new LambdaQueryWrapper<>();
        funcWrapper.eq(FuncKpiConfig::getCategory, categoryName);
        if (funcKpiMapper.selectCount(funcWrapper) > 0) {
            throw new BusinessException(409, "该分类被职能KPI配置引用，无法删除");
        }

        baseMapper.deleteById(id);
    }
}
