{/* 模块用途：ConfirmModal——统一确认弹窗，封装删除确认(红色)和409冲突(黄色)两种场景 */}
{/* 依赖组件：Ant Design Modal */}
{/* 修改注意：所有按钮文案使用中文，不使用OK/Cancel英文 */}
import { Modal } from 'antd';
import { ExclamationCircleOutlined } from '@ant-design/icons';

// 功能：删除确认弹窗——红色标题+不可撤销提示+灰色取消+红色确认删除
// onOk: 确认删除的回调函数
// itemName: 被删除项的名称，用于文案拼接
export function showDeleteConfirm(onOk, itemName = '该记录') {
  Modal.confirm({
    title: `确定要删除${itemName}吗？`,
    icon: <ExclamationCircleOutlined style={{ color: '#FF4D4F' }} />,
    content: '此操作不可撤销。',
    okText: '确认删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    centered: true,
    onOk,
  });
}

// 功能：乐观锁冲突弹窗——黄色警告+修改人信息+刷新页面按钮
// modifier: 修改人的姓名
// modifiedAt: 修改时间
export function showConflictWarning(modifier, modifiedAt) {
  Modal.warning({
    title: '数据已被他人修改',
    icon: <ExclamationCircleOutlined style={{ color: '#FAAD14' }} />,
    content: `${modifier}在${modifiedAt}秒前修改了这条记录。请刷新页面后重新编辑。`,
    okText: '刷新页面',
    centered: true,
    onOk: () => {
      window.location.reload();
    },
  });
}

// 功能：通用确认弹窗——自定义标题+内容+确认按钮类型
// title: 弹窗标题
// content: 弹窗内容
// okText: 确认按钮文字
// okType: 确认按钮类型(primary/danger/default)
// onOk: 确认回调
// cancelText: 取消按钮文字
export function showConfirm({ title, content, okText = '确定', okType = 'primary', cancelText = '取消', onOk }) {
  Modal.confirm({
    title,
    content,
    okText,
    okType,
    cancelText,
    centered: true,
    onOk,
  });
}

