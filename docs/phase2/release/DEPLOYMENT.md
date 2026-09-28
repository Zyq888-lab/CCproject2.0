# Phase2 部署与回滚手册

适用场景：公司内网 Windows Server 手动部署。已知 UAT 应用目录为 `C:\jifeng-assessment\uat`，数据库为本机 PostgreSQL 16 的 `jifeng_uat`。实际服务路径和运行版本仍须在部署时核对。

本手册是操作资料，本次没有执行构建、停服务、复制程序、迁移数据库或轮换口令。部署前先看 [已知问题](KNOWN-ISSUES.md) 和 [验收记录](ACCEPTANCE.md)。

## 一、构建机准备统一交付物

1. 确定最终发布提交，纳入准备发布的本地 Hook 修复；记录提交号和 `VERSION`。当前未提交修改不能被忽略。
2. 在构建机对同一份源码运行后端测试和前端构建。以下是待执行命令，不是本次已通过记录：

```powershell
Set-Location -LiteralPath 'C:\CCproject2.0'
mvn test
if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed' }
mvn package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Backend package failed' }

Set-Location -LiteralPath 'C:\CCproject2.0\frontend'
npm ci
if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency install failed' }
npm run build
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
```

3. 交付 `target\assessment-1.0.0-SNAPSHOT.jar`、完整 `frontend\dist`、本目录文档和需要使用的当前部署脚本。若压缩前端，确保解压后得到完整 `dist`，而不是误多套一层目录。
4. 为 JAR 和最终前端传输包记录 SHA-256，并在服务器上比较。校验值写入 [交付记录](README.md)，本次不预填虚构值：

```powershell
Get-FileHash -LiteralPath 'C:\CCproject2.0\target\assessment-1.0.0-SNAPSHOT.jar' -Algorithm SHA256
```

环境口令、数据库导出、员工业务数据、上传附件和本地日志不放入公开发布包。

## 二、服务器部署前记录与备份

在服务器确认以下项目，记录到交付表：

- 当前运行的 JAR 和前端来源、版本或校验值，以及当前应用服务的实际 PID / 启动方式。
- Java 17、PostgreSQL 16、Nginx 的实际安装路径。仓库脚本默认的 Nginx 路径是 `C:\nginx-1.26.2`，Java 路径是 `C:\Program Files\jdk-17.0.14+7`。
- 目标数据库、成功迁移版本和活动周期，查询见 [迁移清单](DATABASE-MIGRATIONS.md)。
- 旧附件真正所在的目录。若以前使用相对路径，结合旧进程启动目录核对，不把新建的空目录当成已有附件目录。

停止业务写入后，分别保存：数据库一致性备份、旧 JAR、旧 `dist`、Nginx 实际服务目录及配置、全部附件。备份使用单独时间戳目录，不能覆盖上次备份；附件存储可能在应用目录之外，需要单独覆盖。

数据库使用现有数据库管理员的备份方式，或 PostgreSQL 16 的 `pg_dump -Fc`。核对工具退出码、备份文件非空，并用 `pg_restore --list` 验证可读取；有备份文件不等于已经验证可恢复。秘密值按既有安全方式单独维护，不导出原始环境变量块。

## 三、在启动终端准备配置

| 环境变量 | 当前用途 |
| --- | --- |
| `DATASOURCE_URL` | 手动启动时设置为实际 JDBC 地址；UAT 默认 `jdbc:postgresql://localhost:5432/jifeng_uat` |
| `DATASOURCE_USERNAME` | 数据库登录用户；现有 UAT 脚本默认 `postgres`，并非最小权限应用账号 |
| `DATASOURCE_PASSWORD` | 当前真实数据库口令；通过安全方式配置，不写进脚本或文档 |
| `DATASOURCE_DRIVER` | 可使用 `org.postgresql.Driver`，prod 配置已有默认值 |
| `ADMIN_BOOTSTRAP_PASSWORD` | 新建管理员或轮换旧默认管理员口令时所需的强引导口令；现有 UAT 脚本要求非空 |
| `APP_UPLOAD_DIR` | 绝对路径，指向已有、持久、可写的附件目录 |

`ADMIN_BOOTSTRAP_PASSWORD` 须为 12–128 个字符，含大小写字母、数字和特殊字符。已有非默认管理员口令不会被覆盖；被初始化或轮换的管理员需要首次改密。

`POSTGRES_SUPERPASSWORD` 只用于全新 PostgreSQL 安装；重新设置它不会改变已有数据库角色的实际口令。`E2E_ADMIN_PASSWORD` 只用于真实登录的自动测试，不是部署必需变量。

历史数据库口令已确认在 UAT 仍有效；是否使用同一实例、是否完成轮换应单独记录。换成环境变量读取并不等于轮换，详情见 [已知问题](KNOWN-ISSUES.md)。

## 四、人工更新程序

1. 找到实际承载本应用的服务或唯一 Java PID，按现有管理方式停止它，确认旧后端退出。不要仅按进程名停止服务器上的其他 Java 应用。
2. 将已校验的 JAR 放到 `C:\jifeng-assessment\uat\assessment-1.0.0-SNAPSHOT.jar`，将同一提交的前端完整目录放到 `C:\jifeng-assessment\uat\dist`，保留独立备份。
3. 按实际 Nginx 配置，将新前端发布到服务目录；仓库默认是 `C:\nginx-1.26.2\html\jifeng`。不要把 `dist` 备份到应用目录后就认为 Nginx 已更新。
4. 在已准备配置的 PowerShell 终端启动后端。以下命令适用于上述默认 Java 路径；若安装路径不同，先替换：

```powershell
$releaseJava = 'C:\Program Files\jdk-17.0.14+7\bin\java.exe'
$releaseUatDir = 'C:\jifeng-assessment\uat'
$releaseJar = Join-Path $releaseUatDir 'assessment-1.0.0-SNAPSHOT.jar'

if (!(Test-Path -LiteralPath $releaseJava)) { throw 'Java executable not found' }
if (!(Test-Path -LiteralPath $releaseJar)) { throw 'Release JAR not found' }
if ([string]::IsNullOrWhiteSpace($env:DATASOURCE_URL) -or
    [string]::IsNullOrWhiteSpace($env:DATASOURCE_USERNAME) -or
    [string]::IsNullOrWhiteSpace($env:DATASOURCE_PASSWORD) -or
    [string]::IsNullOrWhiteSpace($env:APP_UPLOAD_DIR)) { throw 'Required deployment configuration is missing' }
if (![IO.Path]::IsPathRooted($env:APP_UPLOAD_DIR)) { throw 'APP_UPLOAD_DIR must be absolute' }
if (!(Test-Path -LiteralPath $env:APP_UPLOAD_DIR -PathType Container)) { throw 'Verify the existing attachment directory first' }

Start-Process -FilePath $releaseJava -ArgumentList '-jar', $releaseJar, '--spring.profiles.active=prod' `
  -WorkingDirectory $releaseUatDir -WindowStyle Hidden `
  -RedirectStandardOutput (Join-Path $releaseUatDir 'backend.log') `
  -RedirectStandardError (Join-Path $releaseUatDir 'backend-error.log')
```

若服务器已有正式服务包装或计划任务，应沿用既有启动方式并核对其环境变量，避免再启动第二个后端进程。`prod` 是 Spring profile 名称；UAT 使用该名称，不表示另有一台生产服务器。

## 五、确认启动与迁移

- 检查后端日志中启动成功、数据库连接和 Flyway 执行情况；确认真实库最新成功迁移为 V30，没有失败迁移或校验错误。
- 从服务器访问 `http://localhost`，从内网客户端访问实际应用地址，确认新前端能加载并完成实际登录。
- 使用已登录浏览器确认 `/api/v1/auth/me` 成功，角色和员工归属正确，再检查核心页面。
- 未登录时该接口返回 401 只能证明认证入口有响应，不能证明数据库和全部业务健康。
- 当前仓库未定义 Actuator 健康端点，也没有对应 Actuator 依赖。`deploy-full.ps1` 中 `/api/v1/actuator/health` 的探测不能作为当前版本的有效上线通过依据；本次未修改该脚本。
- 按 [验收清单](ACCEPTANCE.md) 核对旧附件、新附件、权限和结果流程。只有 Java 进程存在或脚本显示 `DONE`，不足以判定部署成功。

## 六、现有脚本适用范围

| 脚本 | 实际做什么 | 使用前核对 |
| --- | --- | --- |
| [deploy-uat.ps1](../../../deploy/uat/deploy-uat.ps1) | 创建/检查 UAT 库、启动已有 JAR、复制前端和写 Nginx 配置 | 不是备份工具；重复启动前核对旧进程和当前配置 |
| [deploy-uat-update.ps1](../../../deploy/uat/deploy-uat-update.ps1) | 重启后端、查询迁移历史 | 不复制新 JAR，不更新 Nginx 前端；按名称停止所有 Java 进程；不能单独代表完整发布 |
| [deploy-full.ps1](../../../deploy/uat/deploy-full.ps1) | 从固定源码目录构建、复制产物、停启后端和 Nginx | 要求服务器有源码与构建工具；后端构建跳过测试；包含目录清理、所有 Java 停止及上述无效健康探测 |

本次资料整理没有修改或执行部署脚本。仅有 RDP 和 UAT 程序目录时，优先依照本手册人工传输与更新，不假定服务器有 `C:\CCproject2.0` 源码目录。

## 七、回滚

1. 停止当前应用业务写入，记录失败原因、已经执行的迁移和升级后新增数据，保存相关日志。
2. 依据迁移兼容性判断能否仅回退程序；经过 V20 字段改名、V23 删除列、V28 唯一键变化等升级后，**不能默认只换旧 JAR 就能运行**。
3. 需要恢复数据库时，先由环境负责人确认备份时间点与新增写入处置，再使用经过验证的备份恢复方案；不在正在运行的数据库上试验恢复。
4. 恢复与数据库版本匹配的后端、前端、附件和配置；数据库凭证使用当前有效值，回退程序不应重新启用已暴露口令。
5. 重新验证启动、登录、数据库迁移历史、已有结果和附件，记录回滚时间和最终运行版本。

代码 `git checkout` 只能恢复工作区文件，不能撤销服务器迁移或恢复业务数据。本仓库没有已验证的自动整套回滚脚本。
