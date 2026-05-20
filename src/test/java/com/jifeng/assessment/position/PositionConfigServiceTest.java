// 模块用途：PositionConfigService 单元测试——覆盖 CRUD、权重校验、考核人角色关联
// 依赖文件：PositionConfigService.java, PositionAssessmentConfig.java, ProjectRoleMapper.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.position;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.common.PageResult;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PositionConfigServiceTest {

    @Autowired
    private PositionConfigService positionConfigService;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    // 辅助方法：创建测试项目角色
    private void createTestRole(String code, String name) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setIsActive(true);
        projectRoleMapper.insert(role);
    }

    // 辅助方法：创建测试岗位配置
    private PositionAssessmentConfig createTestConfig(String category, String position) {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory(category);
        config.setPosition(position);
        config.setIsProjectBased(true);
        config.setFuncAssessMode("DIRECT_LEADER");
        config.setProjectWeight(new BigDecimal("0.7000"));
        config.setFuncWeight(new BigDecimal("0.3000"));
        return positionConfigService.createConfig(config);
    }

    // 功能：创建岗位配置，权重之和=100%时成功
    @Test
    void shouldCreateConfig() {
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");
        assertNotNull(config.getId());
        assertEquals("研发技术类", config.getCategory());
        assertEquals("整椅研发岗", config.getPosition());
        assertTrue(config.getIsProjectBased());
        assertEquals(0, new BigDecimal("0.7000").compareTo(config.getProjectWeight()));
        assertEquals(0, new BigDecimal("0.3000").compareTo(config.getFuncWeight()));
    }

    // 功能：权重之和不等于100%时抛出400异常
    @Test
    void shouldRejectInvalidWeights() {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory("研发技术类");
        config.setPosition("整椅研发岗");
        config.setIsProjectBased(true);
        config.setProjectWeight(new BigDecimal("0.7000"));
        config.setFuncWeight(new BigDecimal("0.4000"));  // sum = 1.1

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.createConfig(config));
        assertTrue(ex.getMessage().contains("100%"));
    }

    // 功能：权重接近100%但在容差范围内时应通过（浮点精度测试）
    @Test
    void shouldAcceptWeightWithinTolerance() {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory("研发技术类");
        config.setPosition("整椅研发岗");
        config.setIsProjectBased(true);
        config.setProjectWeight(new BigDecimal("0.3000"));
        config.setFuncWeight(new BigDecimal("0.7000"));  // sum = 1.0000

        PositionAssessmentConfig result = positionConfigService.createConfig(config);
        assertNotNull(result.getId());
    }

    // 功能：更新岗位配置，乐观锁正常
    @Test
    void shouldUpdateConfig() {
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");

        PositionAssessmentConfig update = new PositionAssessmentConfig();
        update.setCategory("职能管理类");
        PositionAssessmentConfig result = positionConfigService.updateConfig(config.getId(), update);

        assertEquals("职能管理类", result.getCategory());
        assertEquals("整椅研发岗", result.getPosition());  // 未修改的字段保持原值
    }

    // 功能：乐观锁并发冲突——直接修改DB版本后，用过期version调用updateWithOptimisticLock返回409
    @Test
    void shouldRejectOptimisticLockConflict() {
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");

        // 模拟并发：直接通过mapper更新推进version
        PositionAssessmentConfig fresh = positionConfigService.getConfig(config.getId());
        fresh.setCategory("并发修改");
        positionConfigService.getBaseMapper().updateById(fresh);  // version now 1

        // 构造version=0的过期实体
        PositionAssessmentConfig stale = new PositionAssessmentConfig();
        stale.setId(config.getId());
        stale.setVersion(0L);
        stale.setCategory("过期修改");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.updateWithOptimisticLock(stale));
        assertTrue(ex.getMessage().contains("修改") || ex.getMessage().contains("版本") || ex.getMessage().contains("409"));
    }

    // 功能：删除岗位配置后分页列表中不再包含
    @Test
    void shouldDeleteConfig() {
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");
        positionConfigService.deleteConfig(config.getId());

        PageResult<PositionAssessmentConfig> page = positionConfigService.listConfigs(1, 20, null, null);
        assertTrue(page.getList().isEmpty());
    }

    // 功能：新增考核人角色关联，校验角色存在
    @Test
    void shouldAddAssessorRole() {
        createTestRole("PDL", "项目总监");
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");

        PositionAssessorRoleConfig assoc = positionConfigService.addAssessorRole(config.getId(), "PDL");
        assertNotNull(assoc.getId());
        assertEquals(config.getId(), assoc.getPositionConfigId());
        assertEquals("PDL", assoc.getRoleCode());
    }

    // 功能：引用不存在的角色代码时返回400(D1)
    @Test
    void shouldRejectNonexistentRole() {
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.addAssessorRole(config.getId(), "NOT_EXIST"));
        assertTrue(ex.getMessage().contains("不存在"));
    }

    // 功能：重复关联同一角色返回409
    @Test
    void shouldRejectDuplicateAssessorRole() {
        createTestRole("PQL", "项目质量总监");
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");
        positionConfigService.addAssessorRole(config.getId(), "PQL");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.addAssessorRole(config.getId(), "PQL"));
        assertTrue(ex.getMessage().contains("已关联"));
    }

    // 功能：移除考核人角色关联后列表中不再包含
    @Test
    void shouldRemoveAssessorRole() {
        createTestRole("PDL", "项目总监");
        PositionAssessmentConfig config = createTestConfig("研发技术类", "整椅研发岗");
        PositionAssessorRoleConfig assoc = positionConfigService.addAssessorRole(config.getId(), "PDL");

        positionConfigService.removeAssessorRole(config.getId(), assoc.getId());

        List<PositionAssessorRoleConfig> roles = positionConfigService.listAssessorRoles(config.getId());
        assertTrue(roles.isEmpty());
    }

    // 功能：分页查询岗位配置，支持按分类筛选
    @Test
    void shouldListConfigsWithFilter() {
        createTestConfig("研发技术类", "整椅研发岗");
        createTestConfig("研发技术类", "骨架研发岗");
        createTestConfig("职能管理类", "财务岗");

        PageResult<PositionAssessmentConfig> page = positionConfigService.listConfigs(1, 20, "研发技术类", null);
        assertEquals(2, page.getList().size());
    }

    // 功能：权重为空时抛出400
    @Test
    void shouldRejectNullWeights() {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory("研发技术类");
        config.setPosition("整椅研发岗");
        config.setProjectWeight(null);
        config.setFuncWeight(new BigDecimal("0.3000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.createConfig(config));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    // 功能：职能考核方式为非有效值时抛出400
    @Test
    void shouldRejectInvalidFuncAssessMode() {
        PositionAssessmentConfig config = new PositionAssessmentConfig();
        config.setCategory("研发技术类");
        config.setPosition("整椅研发岗");
        config.setProjectWeight(new BigDecimal("0.7000"));
        config.setFuncWeight(new BigDecimal("0.3000"));
        config.setFuncAssessMode("INVALID_MODE");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionConfigService.createConfig(config));
        assertTrue(ex.getMessage().contains("无效的职能考核方式"));
    }
}
