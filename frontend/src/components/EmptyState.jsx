{/* 模块用途：EmptyState——统一空状态组件，插画+标题+引导文案+CTA按钮 */}
{/* 依赖组件：Ant Design Empty/Button */}
{/* 修改注意：各页面空状态规格见eng-plan 13.7表格，按钮文案全中文 */}
import { Empty, Button, Space } from 'antd';

// 功能：渲染空状态引导页——自定义插画+标题+描述文案+最多2个操作按钮
// image: React节点（插画图标）
// title: 主标题文字
// description: 引导文案
// primaryAction: { label, onClick } — 蓝色主按钮
// secondaryAction: { label, onClick } — 默认次按钮
function EmptyState({ image, title, description, primaryAction, secondaryAction }) {
  return (
    <div id="empty-state-area" style={{
      display: 'flex',
      justifyContent: 'center',
      alignItems: 'center',
      minHeight: 400,
    }}>
      <div style={{ textAlign: 'center', maxWidth: 480 }}>
        {/* 功能：自定义空状态插画——使用Ant Design Empty或传入自定义SVG */}
        <div style={{ marginBottom: 24 }}>
          {image || <Empty description={null} />}
        </div>

        {/* 功能：标题文字——20px字号，字重500 */}
        <h2 style={{ fontSize: 20, fontWeight: 500, color: '#333', marginBottom: 8 }}>
          {title || '暂无数据'}
        </h2>

        {/* 功能：引导文案——14px辅助文字颜色 */}
        {description && (
          <p style={{ color: '#8C8C8C', fontSize: 14, marginBottom: 24, lineHeight: 1.6 }}>
            {description}
          </p>
        )}

        {/* 功能：操作按钮——主按钮(type=primary)+次按钮(type=default) */}
        {(primaryAction || secondaryAction) && (
          <div id="empty-state-actions">
            <Space size={12}>
              {primaryAction && (
                <Button type="primary" onClick={primaryAction.onClick} size="large">
                  {primaryAction.label}
                </Button>
              )}
              {secondaryAction && (
                <Button onClick={secondaryAction.onClick} size="large">
                  {secondaryAction.label}
                </Button>
              )}
            </Space>
          </div>
        )}
      </div>
    </div>
  );
}

export default EmptyState;
