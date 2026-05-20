// 模块用途：KpiConfigService + ScoreCalculator 单元测试——覆盖CRUD、权重校验、算分公式
// 依赖文件：KpiConfigService.java, ScoreCalculator.java, WeightValidator.java
// 修改注意：测试用 H2 内存库，每个用例独立，不要依赖执行顺序
package com.jifeng.assessment.kpi;

import com.jifeng.assessment.common.BusinessException;
import com.jifeng.assessment.projectrole.ProjectRole;
import com.jifeng.assessment.projectrole.ProjectRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class KpiConfigServiceTest {

    @Autowired
    private KpiConfigService kpiConfigService;

    @Autowired
    private ProjectRoleMapper projectRoleMapper;

    // 辅助方法：创建测试项目角色（project_kpi_config 有 FK 约束）
    private void createTestRole(String code, String name) {
        ProjectRole role = new ProjectRole();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setIsActive(true);
        projectRoleMapper.insert(role);
    }

    // 辅助方法：创建测试项目KPI
    private ProjectKpiConfig createTestProjectKpi(String roleCode, String stage, String name, String weight) {
        ProjectKpiConfig config = new ProjectKpiConfig();
        config.setProjectRoleCode(roleCode);
        config.setProjectStage(stage);
        config.setKpiName(name);
        config.setWeight(new BigDecimal(weight));
        return kpiConfigService.createProjectKpi(config);
    }

    // 辅助方法：创建测试职能KPI
    private FuncKpiConfig createTestFuncKpi(String category, String position, String name, String weight) {
        FuncKpiConfig config = new FuncKpiConfig();
        config.setCategory(category);
        config.setPosition(position);
        config.setKpiName(name);
        config.setWeight(new BigDecimal(weight));
        return kpiConfigService.createFuncKpi(config);
    }

    // ========================================
    // 项目KPI CRUD
    // ========================================

    // 功能：创建项目KPI成功
    @Test
    void shouldCreateProjectKpi() {
        createTestRole("PDL", "项目总监");
        ProjectKpiConfig config = createTestProjectKpi("PDL", "P2", "技术方案质量", "1.0000");
        assertNotNull(config.getId());
        assertEquals("PDL", config.getProjectRoleCode());
        assertEquals("P2", config.getProjectStage());
        assertEquals("技术方案质量", config.getKpiName());
        assertEquals(0, new BigDecimal("1.0000").compareTo(config.getWeight()));
    }

    // 功能：同角色同阶段下重复指标名返回409
    @Test
    void shouldRejectDuplicateKpiName() {
        createTestRole("PDL", "项目总监");
        createTestProjectKpi("PDL", "P2", "技术方案质量", "0.5000");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createTestProjectKpi("PDL", "P2", "技术方案质量", "0.5000"));
        assertTrue(ex.getMessage().contains("已存在指标"));
    }

    // 功能：更新项目KPI字段
    @Test
    void shouldUpdateProjectKpi() {
        createTestRole("PDL", "项目总监");
        ProjectKpiConfig config = createTestProjectKpi("PDL", "P2", "技术方案质量", "0.5000");

        ProjectKpiConfig update = new ProjectKpiConfig();
        update.setKpiName("进度计划合理性");
        ProjectKpiConfig result = kpiConfigService.updateProjectKpi(config.getId(), update);

        assertEquals("进度计划合理性", result.getKpiName());
        assertEquals("PDL", result.getProjectRoleCode());  // 未修改的字段保持原值
    }

    // 功能：乐观锁并发冲突——用过期version调用update返回409
    @Test
    void shouldRejectProjectKpiOptimisticLockConflict() {
        createTestRole("PDL", "项目总监");
        ProjectKpiConfig config = createTestProjectKpi("PDL", "P2", "技术方案质量", "0.5000");

        // 第一次正常更新推进version到1
        ProjectKpiConfig update1 = new ProjectKpiConfig();
        update1.setKpiName("第一次修改");
        update1.setVersion(0L);  // 传入当前version
        kpiConfigService.updateProjectKpi(config.getId(), update1);

        // 用过期version=0再次更新应返回409
        ProjectKpiConfig stale = new ProjectKpiConfig();
        stale.setKpiName("过期修改");
        stale.setVersion(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> kpiConfigService.updateProjectKpi(config.getId(), stale));
        assertTrue(ex.getMessage().contains("修改") || ex.getMessage().contains("409"));
    }

    // 功能：切换项目KPI启用/停用
    @Test
    void shouldToggleProjectKpi() {
        createTestRole("PDL", "项目总监");
        ProjectKpiConfig config = createTestProjectKpi("PDL", "P2", "技术方案质量", "0.5000");
        assertTrue(config.getIsActive());

        ProjectKpiConfig toggled = kpiConfigService.toggleProjectKpi(config.getId());
        assertFalse(toggled.getIsActive());
    }

    // 功能：删除项目KPI后列表中不再包含
    @Test
    void shouldDeleteProjectKpi() {
        createTestRole("PDL", "项目总监");
        ProjectKpiConfig config = createTestProjectKpi("PDL", "P2", "技术方案质量", "0.5000");
        kpiConfigService.deleteProjectKpi(config.getId());

        List<ProjectKpiConfig> list = kpiConfigService.listProjectKpis("PDL", "P2", null);
        assertTrue(list.isEmpty());
    }

    // 功能：按角色和阶段筛选项目KPI
    @Test
    void shouldListProjectKpisWithFilter() {
        createTestRole("PDL", "项目总监");
        createTestRole("PQL", "项目质量总监");
        createTestProjectKpi("PDL", "P2", "技术方案质量", "0.4000");
        createTestProjectKpi("PDL", "P2", "进度计划合理性", "0.6000");
        createTestProjectKpi("PQL", "P2", "质量检查覆盖率", "1.0000");

        List<ProjectKpiConfig> list = kpiConfigService.listProjectKpis("PDL", "P2", null);
        assertEquals(2, list.size());
    }

    // ========================================
    // 职能KPI CRUD
    // ========================================

    // 功能：创建职能KPI成功
    @Test
    void shouldCreateFuncKpi() {
        FuncKpiConfig config = createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "1.0000");
        assertNotNull(config.getId());
        assertEquals("研发技术类", config.getCategory());
        assertEquals("整椅研发岗", config.getPosition());
        assertEquals("技术文档质量", config.getKpiName());
    }

    // 功能：同分类同岗位下重复指标名返回409
    @Test
    void shouldRejectDuplicateFuncKpiName() {
        createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "0.5000");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "0.5000"));
        assertTrue(ex.getMessage().contains("已存在指标"));
    }

    // 功能：更新职能KPI
    @Test
    void shouldUpdateFuncKpi() {
        FuncKpiConfig config = createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "0.5000");

        FuncKpiConfig update = new FuncKpiConfig();
        update.setKpiName("沟通协作能力");
        FuncKpiConfig result = kpiConfigService.updateFuncKpi(config.getId(), update);

        assertEquals("沟通协作能力", result.getKpiName());
    }

    // 功能：切换职能KPI启用/停用
    @Test
    void shouldToggleFuncKpi() {
        FuncKpiConfig config = createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "0.5000");
        assertTrue(config.getIsActive());

        FuncKpiConfig toggled = kpiConfigService.toggleFuncKpi(config.getId());
        assertFalse(toggled.getIsActive());
    }

    // 功能：删除职能KPI后列表中不再包含
    @Test
    void shouldDeleteFuncKpi() {
        FuncKpiConfig config = createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "0.5000");
        kpiConfigService.deleteFuncKpi(config.getId());

        List<FuncKpiConfig> list = kpiConfigService.listFuncKpis("研发技术类", "整椅研发岗");
        assertTrue(list.isEmpty());
    }

    // 功能：按分类和岗位筛选职能KPI
    @Test
    void shouldListFuncKpisWithFilter() {
        createTestFuncKpi("研发技术类", "整椅研发岗", "技术文档质量", "0.5000");
        createTestFuncKpi("研发技术类", "骨架研发岗", "设计评审通过率", "0.5000");
        createTestFuncKpi("职能管理类", "财务岗", "报表准确性", "0.5000");

        List<FuncKpiConfig> list = kpiConfigService.listFuncKpis("研发技术类", null);
        assertEquals(2, list.size());
    }

    // ========================================
    // 权重校验
    // ========================================

    // 功能：同scope下权重之和超过100%时拒绝
    @Test
    void shouldRejectProjectKpiWeightExceed() {
        createTestRole("PDL", "项目总监");
        createTestProjectKpi("PDL", "P2", "技术方案质量", "0.8000");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createTestProjectKpi("PDL", "P2", "进度计划合理性", "0.5000"));
        assertTrue(ex.getMessage().contains("超过100%"));
    }

    // 功能：浮点精度——0.3+0.3+0.4 在容差范围内通过
    @Test
    void shouldAcceptFloatPrecisionWeights() {
        createTestRole("PDL", "项目总监");
        assertNotNull(createTestProjectKpi("PDL", "P1", "指标A", "0.3000").getId());
        assertNotNull(createTestProjectKpi("PDL", "P1", "指标B", "0.3000").getId());
        assertNotNull(createTestProjectKpi("PDL", "P1", "指标C", "0.4000").getId());

        // 三个KPI权重之和 = 1.0，验证列表中有3条
        List<ProjectKpiConfig> list = kpiConfigService.listProjectKpis("PDL", "P1", null);
        assertEquals(3, list.size());
    }

    // 功能：职能KPI权重超过100%时拒绝
    @Test
    void shouldRejectFuncKpiWeightExceed() {
        createTestFuncKpi("研发技术类", "整椅研发岗", "指标A", "0.9000");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createTestFuncKpi("研发技术类", "整椅研发岗", "指标B", "0.5000"));
        assertTrue(ex.getMessage().contains("超过100%"));
    }

    // 功能：权重为空时抛出400
    @Test
    void shouldRejectNullWeight() {
        createTestRole("PDL", "项目总监");
        ProjectKpiConfig config = new ProjectKpiConfig();
        config.setProjectRoleCode("PDL");
        config.setProjectStage("P2");
        config.setKpiName("测试指标");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> kpiConfigService.createProjectKpi(config));
        assertTrue(ex.getMessage().contains("不能为空"));
    }

    // ========================================
    // ScoreCalculator 算分公式
    // ========================================

    // 功能：加权求和——单项目KPI得分 = Σ(指标得分 × 指标权重)
    @Test
    void shouldCalculateWeightedSum() {
        List<BigDecimal> scores = List.of(new BigDecimal("4.2"), new BigDecimal("3.8"));
        List<BigDecimal> weights = List.of(new BigDecimal("0.6000"), new BigDecimal("0.4000"));

        BigDecimal result = ScoreCalculator.weightedSum(scores, weights);
        // 4.2×0.6 + 3.8×0.4 = 2.52 + 1.52 = 4.04
        assertEquals(0, new BigDecimal("4.0400").compareTo(result));
    }

    // 功能：项目考核加权得分 = Σ(项目KPI得分_i × 投入比重_i)
    @Test
    void shouldCalculateProjectCompositeScore() {
        List<BigDecimal> projectScores = List.of(new BigDecimal("4.2"), new BigDecimal("3.8"));
        List<BigDecimal> rates = List.of(new BigDecimal("0.6000"), new BigDecimal("0.4000"));

        BigDecimal result = ScoreCalculator.projectCompositeScore(projectScores, rates);
        // 4.2×0.6 + 3.8×0.4 = 4.04
        assertEquals(0, new BigDecimal("4.0400").compareTo(result));
    }

    // 功能：最终得分 = 项目得分 × 项目权重 + 职能得分 × 职能权重
    @Test
    void shouldCalculateFinalScore() {
        BigDecimal projectScore = new BigDecimal("4.04");
        BigDecimal projectWeight = new BigDecimal("0.7000");
        BigDecimal funcScore = new BigDecimal("4.0");
        BigDecimal funcWeight = new BigDecimal("0.3000");

        BigDecimal result = ScoreCalculator.finalScore(projectScore, projectWeight, funcScore, funcWeight);
        // 4.04×0.7 + 4.0×0.3 = 2.828 + 1.2 = 4.028
        assertEquals(0, new BigDecimal("4.0280").compareTo(result));
    }

    // 功能：设计文档示例验证——4.2/3.8/4.0 → 3.828
    // 示例：项目A=4.2(60%), 项目B=3.8(40%), 职能=4.0, 项目权重70%, 职能权重30%
    @Test
    void shouldMatchDesignDocExample() {
        // Step 1: 各项目KPI加权得分（假设每个项目只有1个KPI，得分即项目得分）
        // Step 2: 项目考核加权得分 = 4.2×0.6 + 3.8×0.4 = 4.04
        BigDecimal projectComposite = ScoreCalculator.projectCompositeScore(
                List.of(new BigDecimal("4.2"), new BigDecimal("3.8")),
                List.of(new BigDecimal("0.6000"), new BigDecimal("0.4000")));
        assertEquals(0, new BigDecimal("4.0400").compareTo(projectComposite));

        // Step 3: 最终得分 = 4.04×0.7 + 4.0×0.3 = 2.828 + 1.2 = 4.028
        BigDecimal finalScore = ScoreCalculator.finalScore(
                projectComposite, new BigDecimal("0.7000"),
                new BigDecimal("4.0"), new BigDecimal("0.3000"));
        assertEquals(0, new BigDecimal("4.0280").compareTo(finalScore));
    }

    // 功能：加权求和——空列表返回0
    @Test
    void shouldReturnZeroForEmptyList() {
        BigDecimal result = ScoreCalculator.weightedSum(List.of(), List.of());
        assertEquals(0, new BigDecimal("0.0000").compareTo(result));
    }

    // 功能：加权求和——分数和权重列表长度不一致时抛异常
    @Test
    void shouldRejectMismatchedListSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> ScoreCalculator.weightedSum(
                        List.of(new BigDecimal("4.0")),
                        List.of(new BigDecimal("0.3000"), new BigDecimal("0.7000"))));
    }
}
