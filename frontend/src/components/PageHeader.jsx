{/* 模块用途：PageHeader——统一页面标题栏，左侧标题+面包屑，右侧操作按钮 */}
{/* 依赖组件：Ant Design PageHeader/Typography/Button */}
{/* 修改注意：操作按钮通过actions数组传入，每个按钮含label/icon/onClick/type */}
import { Typography, Button, Space, Breadcrumb } from 'antd';
import { useNavigate } from 'react-router-dom';

const { Title } = Typography;

// 功能：渲染页面标题栏——标题+可选面包屑+操作按钮区
// actions: [{ label, icon, onClick, type, danger }]
// breadcrumb: [{ title, path }]
function PageHeader({ title, breadcrumb, actions }) {
  const navigate = useNavigate();

  return (
    <div id="page-header-area" style={{
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 16,
    }}>
      <div id="page-header-left">
        {/* 功能：面包屑导航——点击可跳转到上级页面 */}
        {breadcrumb && breadcrumb.length > 0 && (
          <Breadcrumb
            style={{ marginBottom: 4 }}
            items={breadcrumb.map((item) => ({
              key: item.title || item.path,
              title: item.path ? (
                <a onClick={() => navigate(item.path)}>{item.title}</a>
              ) : (
                item.title
              ),
            }))}
          />
        )}
        {/* 功能：页面标题——20px字号，字重500 */}
        <Title level={4} style={{ margin: 0, fontSize: 20, fontWeight: 500 }}>
          {title}
        </Title>
      </div>

      {/* 功能：操作按钮区——蓝色主按钮(type=primary)+默认次要按钮 */}
      {actions && actions.length > 0 && (
        <div id="page-header-actions">
          <Space>
            {actions.map((action, idx) => (
              <Button
                key={idx}
                type={action.type || 'default'}
                icon={action.icon}
                danger={action.danger}
                onClick={action.onClick}
              >
                {action.label}
              </Button>
            ))}
          </Space>
        </div>
      )}
    </div>
  );
}

export default PageHeader;
