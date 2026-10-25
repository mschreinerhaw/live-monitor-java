param([switch]$SkipNpmInstall)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$backend = Join-Path $repository 'live-monitor-java'
$frontend = Join-Path $backend 'frontend'
$output = Join-Path $repository 'target/frontend-dist'
$buildDirectory = Join-Path $repository 'target/container-platform-build'
function Invoke-BuildCommand {
    param([string]$Executable, [string[]]$Arguments)
    # Windows PowerShell wraps native stderr progress as ErrorRecord. Exit code is authoritative.
    $ErrorActionPreference = 'Continue'
    & $Executable @Arguments 2>&1 | ForEach-Object { Write-Output "$_" }
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}
Push-Location $frontend
try {
    if (!$SkipNpmInstall) { Invoke-BuildCommand npm.cmd @('ci','--no-audit','--no-fund') }
    Invoke-BuildCommand npm.cmd @('test')
    Invoke-BuildCommand node @('node_modules/@vue/cli-service/bin/vue-cli-service.js','build','--dest',$output)
} finally { Pop-Location }
Push-Location $backend
try {
    Invoke-BuildCommand mvn.cmd @('package','-Pcontainer-platform',"-Dcontainer.ui.dir=$output","-Dcontainer.build.dir=$buildDirectory")
} finally { Pop-Location }
Write-Output "Built $buildDirectory/live-monitor-java-1.0.0.jar; no deployment executed."
