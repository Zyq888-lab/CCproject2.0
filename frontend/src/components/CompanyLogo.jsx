import companyLogo from '../assets/jfseat-grammer.png';

function CompanyLogo({ compact = false, style }) {
  // 使用原图的可见区域，避开透明留白；折叠时展示 JFSEAT 图形标识。
  const viewBox = compact ? '790 721 520 521' : '572 721 2417 777';

  return (
    <svg
      role="img"
      aria-label={compact ? 'JFSEAT 公司 Logo' : 'JFSEAT 和 GRAMMER 公司 Logo'}
      viewBox={viewBox}
      preserveAspectRatio="xMidYMid meet"
      focusable="false"
      style={{ display: 'block', width: compact ? 32 : 168, height: compact ? 32 : 54, ...style }}
    >
      <image href={companyLogo} width="3508" height="2481" />
    </svg>
  );
}

export default CompanyLogo;
