{/* 模块用途：FuncScorePage——职能KPI打分页，复用 ProjectScorePage 结构，指标来源 func_kpi_config */}
{/* 依赖组件：ProjectScorePage.jsx */}
{/* 修改注意：kpiType 固定为 FUNCTIONAL，指标来自职能KPI配置表 */}
import ProjectScorePage from './ProjectScorePage';

// 功能：职能考核打分——复用项目打分页的 Slider/凭证上传/二次确认/乐观锁冲突逻辑
function FuncScorePage() {
  return <ProjectScorePage kpiType="FUNCTIONAL" />;
}

export default FuncScorePage;
