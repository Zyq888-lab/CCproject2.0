$ErrorActionPreference = "Stop"

# ============================================================
#  Jifeng Assessment — 完整 UAT 部署脚本
#  用法: 在项目根目录 C:\CCproject2.0 下执行
#        powershell -File deploy\uat\deploy-full.ps1
# ============================================================

$PROJECT_ROOT = "C:\CCproject2.0"
$FRONTEND_DIR = "$PROJECT_ROOT\frontend"
$UAT_DIR      = "C:\jifeng-assessment\uat"
$NGINX_HTML   = "C:\nginx-1.26.2\html\jifeng"
$NGINX_EXE    = "C:\nginx-1.26.2\nginx.exe"
$JAVA_EXE     = "C:\Program Files\jdk-17.0.14+7\bin\java.exe"
$JAR_NAME     = "assessment-1.0.0-SNAPSHOT.jar"

# ---- DB 环境变量 ----
$env:DATASOURCE_URL      = "jdbc:postgresql://localhost:5432/jifeng_uat"
$env:DATASOURCE_DRIVER   = "org.postgresql.Driver"
$env:DATASOURCE_USERNAME = "postgres"
$env:DATASOURCE_PASSWORD = "P@ssw0rd123"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Jifeng Assessment UAT Full Deploy" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ---- Step 1: Build frontend ----
Write-Host "`n[1/5] Building frontend..." -ForegroundColor Yellow
Set-Location $FRONTEND_DIR
npm run build
if ($LASTEXITCODE -ne 0) { throw "Frontend build failed" }
Write-Host "  OK: Frontend built" -ForegroundColor Green

# ---- Step 2: Build backend ----
Write-Host "`n[2/5] Building backend..." -ForegroundColor Yellow
Set-Location $PROJECT_ROOT
mvn package -DskipTests -q
if ($LASTEXITCODE -ne 0) { throw "Backend build failed" }
Write-Host "  OK: Backend built" -ForegroundColor Green

# ---- Step 3: Copy artifacts to UAT dir ----
Write-Host "`n[3/5] Copying artifacts to $UAT_DIR ..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $UAT_DIR | Out-Null

# copy jar
Copy-Item "$PROJECT_ROOT\target\$JAR_NAME" "$UAT_DIR\$JAR_NAME" -Force
Write-Host "  OK: JAR copied"

# copy dist
if (Test-Path "$UAT_DIR\dist") { Remove-Item "$UAT_DIR\dist" -Recurse -Force }
Copy-Item "$FRONTEND_DIR\dist" "$UAT_DIR\dist" -Recurse
Write-Host "  OK: dist copied" -ForegroundColor Green

# ---- Step 4: Stop old services & deploy ----
Write-Host "`n[4/5] Restarting services..." -ForegroundColor Yellow

# stop old nginx
& $NGINX_EXE -s stop 2>$null
Write-Host "  Nginx stopped"

# stop old java
Get-Process -Name "java" -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "  Java stopped"

# start backend
Start-Process $JAVA_EXE -ArgumentList "-jar", "$UAT_DIR\$JAR_NAME", "--spring.profiles.active=prod" -WindowStyle Hidden
Write-Host "  Backend started on http://localhost:8080"

# deploy frontend to nginx
New-Item -ItemType Directory -Force -Path $NGINX_HTML | Out-Null
Copy-Item "$UAT_DIR\dist\*" $NGINX_HTML -Recurse -Force

# ensure nginx conf.d include (idempotent)
$confDir = "C:\nginx-1.26.2\conf\conf.d"
New-Item -ItemType Directory -Force -Path $confDir | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$nginxConf = @"
server {
    listen       80;
    server_name  localhost;
    location / {
        root   html/jifeng;
        index  index.html;
        try_files `$uri `$uri/ /index.html;
    }
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host `$host;
        proxy_set_header X-Real-IP `$remote_addr;
    }
}
"@
[System.IO.File]::WriteAllText("$confDir\jifeng.conf", $nginxConf, $utf8NoBom)

$mainConf = "C:\nginx-1.26.2\conf\nginx.conf"
$mainContent = Get-Content $mainConf -Raw
if ($mainContent -notmatch "conf.d") {
    $includeLine = "    include conf.d/*.conf;"
    $mainContent = $mainContent -replace "(http\s*\{)", "`$1`r`n$includeLine"
    [System.IO.File]::WriteAllText($mainConf, $mainContent, $utf8NoBom)
    Write-Host "  conf.d include added to nginx.conf"
}

# start nginx
Start-Process $NGINX_EXE
Write-Host "  Nginx started"

Write-Host "  OK: Services restarted" -ForegroundColor Green

# ---- Step 5: Smoke check ----
Write-Host "`n[5/5] Smoke check..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

try {
    $api = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/actuator/health" -TimeoutSec 10
    Write-Host "  Backend: OK ($($api.status))" -ForegroundColor Green
} catch {
    Write-Host "  Backend: NOT READY ($_)" -ForegroundColor Red
}

try {
    $fe = Invoke-WebRequest -Uri "http://localhost" -TimeoutSec 5 -UseBasicParsing
    Write-Host "  Frontend: OK ($($fe.StatusCode))" -ForegroundColor Green
} catch {
    Write-Host "  Frontend: NOT READY ($_)" -ForegroundColor Red
}

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  UAT Deployment Complete!" -ForegroundColor Cyan
Write-Host "  Access: http://localhost" -ForegroundColor Green
Write-Host "  Login:  admin / admin123" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
