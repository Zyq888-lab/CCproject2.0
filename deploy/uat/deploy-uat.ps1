$ErrorActionPreference = "Continue"
$DEPLOY_DIR = "C:\jifeng-assessment\uat"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Jifeng Assessment UAT Deploy" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Create UAT database
Write-Host "[1/4] Creating UAT database..." -ForegroundColor Yellow
$env:PGPASSWORD = "P@ssw0rd123"
$psql = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
$dbCheck = & $psql -U postgres -t -c "SELECT 1 FROM pg_database WHERE datname='jifeng_uat'" 2>&1
if ($dbCheck -notmatch "1") {
    & $psql -U postgres -c "CREATE DATABASE jifeng_uat" 2>&1
    Write-Host "  OK: Database jifeng_uat created" -ForegroundColor Green
} else {
    Write-Host "  OK: Database jifeng_uat already exists" -ForegroundColor Green
}

# 2. Start backend
Write-Host "[2/4] Starting backend..." -ForegroundColor Yellow
$env:DATASOURCE_URL = "jdbc:postgresql://localhost:5432/jifeng_uat"
$env:DATASOURCE_DRIVER = "org.postgresql.Driver"
$env:DATASOURCE_USERNAME = "postgres"
$env:DATASOURCE_PASSWORD = "P@ssw0rd123"
$java = "C:\Program Files\jdk-17.0.14+7\bin\java.exe"
$jar = Join-Path $DEPLOY_DIR "assessment-1.0.0-SNAPSHOT.jar"
if (Test-Path $jar) {
    Start-Process $java -ArgumentList "-jar", "$jar", "--spring.profiles.active=prod" -WindowStyle Hidden
    Write-Host "  OK: Backend started on http://localhost:8080" -ForegroundColor Green
} else {
    Write-Host "  ERROR: JAR not found at $jar" -ForegroundColor Red
}

# 3. Deploy frontend
Write-Host "[3/4] Deploying frontend..." -ForegroundColor Yellow
$nginxHtml = "C:\nginx-1.26.2\html\jifeng"
New-Item -ItemType Directory -Force -Path $nginxHtml | Out-Null
$distSource = Join-Path $DEPLOY_DIR "dist"
if (Test-Path $distSource) {
    Copy-Item "$distSource\*" $nginxHtml -Recurse -Force
    Write-Host "  OK: Frontend copied to $nginxHtml" -ForegroundColor Green
} else {
    Write-Host "  ERROR: dist not found at $distSource" -ForegroundColor Red
}

# 4. Write nginx config and start
Write-Host "[4/4] Configuring Nginx..." -ForegroundColor Yellow
$confDir = "C:\nginx-1.26.2\conf\conf.d"
New-Item -ItemType Directory -Force -Path $confDir | Out-Null
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
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText("$confDir\jifeng.conf", $nginxConf, $utf8NoBom)

# Include conf.d in main nginx.conf if not already included
$mainConf = "C:\nginx-1.26.2\conf\nginx.conf"
$mainContent = Get-Content $mainConf -Raw
if ($mainContent -notmatch "conf.d") {
    $includeLine = "    include conf.d/*.conf;"
    $mainContent = $mainContent -replace "(http\s*\{)", "`$1`r`n$includeLine"
    [System.IO.File]::WriteAllText($mainConf, $mainContent, $utf8NoBom)
}

# Start Nginx
Start-Process "C:\nginx-1.26.2\nginx.exe"
Write-Host "  OK: Nginx started" -ForegroundColor Green

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  UAT Deployment Complete!" -ForegroundColor Cyan
Write-Host "  Access: http://localhost from server" -ForegroundColor Green
Write-Host "  Login:  admin / admin123" -ForegroundColor White
Write-Host "========================================" -ForegroundColor Cyan
