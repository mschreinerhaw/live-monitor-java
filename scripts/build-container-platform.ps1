param([switch]$SkipNpmInstall)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $repository 'live-monitor-java'
$frontend = Join-Path $backend 'frontend'
$output = Join-Path $repository 'target/frontend-dist'
$buildDirectory = Join-Path $repository 'target/container-platform-build'
Push-Location $frontend
try {
    if (!$SkipNpmInstall) { & npm.cmd ci --no-audit --no-fund; if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' } }
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw 'Vue3 unit tests failed' }
    & node node_modules/@vue/cli-service/bin/vue-cli-service.js build --dest $output
    if ($LASTEXITCODE -ne 0) { throw 'Vue3 build failed' }
} finally { Pop-Location }
Push-Location $backend
try {
    & mvn.cmd package -Pcontainer-platform "-Dcontainer.ui.dir=$output" "-Dcontainer.build.dir=$buildDirectory"
    if ($LASTEXITCODE -ne 0) { throw 'Java tests or package failed' }
} finally { Pop-Location }
Write-Output "Built $buildDirectory/live-monitor-java-1.0.0.jar; no deployment executed."
