param(
    [Parameter(Mandatory = $true)][string]$Artifact,
    [Parameter(Mandatory = $true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256,
    [string]$StorageRoot = '',
    [int]$Port = 18080
)
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$StorageRoot) { $StorageRoot = Join-Path $repository 'target/runtime-os-production-control' }
$runtimeRoot = [IO.Path]::GetFullPath($StorageRoot)
$artifactPath = (Resolve-Path -LiteralPath $Artifact).Path
if ((Get-FileHash -LiteralPath $artifactPath -Algorithm SHA256).Hash -ine $ExpectedSha256) { throw 'Artifact SHA256 does not match the verified build' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Choose an unprivileged TCP port' }
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$secretPath = Join-Path $runtimeRoot 'secrets-private.json'
function New-PlatformSecret {
    $bytes = New-Object byte[] 48
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
if (!(Test-Path -LiteralPath $secretPath)) {
    if (Test-Path -LiteralPath (Join-Path $runtimeRoot 'data')) { throw 'Existing data has no stable key file; do not replace its encryption identity' }
    @{ encryptionKey = (New-PlatformSecret); databasePassword = (New-PlatformSecret); username = 'admin'; password = (New-PlatformSecret) } |
        ConvertTo-Json | Set-Content -LiteralPath $secretPath -Encoding UTF8
    & icacls.exe $secretPath /inheritance:r /grant:r "${env:USERNAME}:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not protect local credentials' }
}
$secrets = Get-Content -LiteralPath $secretPath -Raw -Encoding UTF8 | ConvertFrom-Json
$pidPath = Join-Path $runtimeRoot 'platform.pid'
if (Test-Path -LiteralPath $pidPath) {
    $previousId = [int](Get-Content -LiteralPath $pidPath -Raw)
    $previous = Get-CimInstance Win32_Process -Filter "ProcessId=$previousId"
    if ($previous) { throw 'The recorded local platform is still running; reconcile its PID before replacing the JAR' }
}
$jarPath = Join-Path $runtimeRoot 'app.jar'
Copy-Item -LiteralPath $artifactPath -Destination $jarPath -Force
$env:LIVE_MONITOR_SECRET_KEY = $secrets.encryptionKey
$env:LIVE_MONITOR_DB_PASSWORD = $secrets.databasePassword
$arguments = @('-jar', ('"' + $jarPath + '"'), '--server.address=127.0.0.1', "--server.port=$Port",
    '"--spring.datasource.url=jdbc:h2:file:./data/platform;MODE=LEGACY;DATABASE_TO_LOWER=TRUE"',
    '--live-monitor.rocksdb-path=./data/rocksdb', '--live-monitor.sqlite-path=./data/absent.db',
    '--live-monitor.h2-legacy-path=./data/absent-h2', '--debug=false', '--logging.level.root=INFO')
try {
    $java = (Get-Command java.exe).Source
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $runtimeRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runtimeRoot 'server.log') -RedirectStandardError (Join-Path $runtimeRoot 'stderr.log')
    $process.Id | Set-Content -LiteralPath $pidPath -NoNewline
} finally {
    Remove-Item Env:LIVE_MONITOR_SECRET_KEY,Env:LIVE_MONITOR_DB_PASSWORD -ErrorAction SilentlyContinue
}
$ready = $false
for ($attempt = 0; $attempt -lt 60; $attempt++) {
    $process.Refresh()
    if ($process.HasExited) { throw 'The new local platform exited; inspect its private stderr/server logs' }
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue | Where-Object { $_.OwningProcess -eq $process.Id }
    if ($listener) {
        try { $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 3; if ($health.status -eq 'ok') { $ready = $true; break } } catch {}
    }
    Start-Sleep -Milliseconds 1000
}
if (!$ready) { throw 'The owned local platform did not become ready' }
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$baseUri = "http://127.0.0.1:$Port"
try {
    Invoke-RestMethod -Uri "$baseUri/api/auth/login" -Method Post -ContentType 'application/json' -WebSession $session -Body (@{ username = $secrets.username; password = $secrets.password } | ConvertTo-Json) | Out-Null
} catch {
    Invoke-RestMethod -Uri "$baseUri/api/auth/login" -Method Post -ContentType 'application/json' -WebSession $session -Body (@{ username = 'admin'; password = '000000' } | ConvertTo-Json) | Out-Null
    Invoke-RestMethod -Uri "$baseUri/api/admin/users/admin/password" -Method Put -ContentType 'application/json' -WebSession $session -Body (@{ new_password = $secrets.password } | ConvertTo-Json) | Out-Null
}
$loginPath = Join-Path $runtimeRoot 'login-private.json'
@{ username = $secrets.username; password = $secrets.password; baseUrl = $baseUri } | ConvertTo-Json | Set-Content -LiteralPath $loginPath -Encoding UTF8
& icacls.exe $loginPath /inheritance:r /grant:r "${env:USERNAME}:(F)" | Out-Null
Write-Output "Local operations control plane started: PID $($process.Id), http://127.0.0.1:$Port/; persistent data in $runtimeRoot"
