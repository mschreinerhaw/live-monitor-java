param(
    [ValidateRange(1024,65535)][int]$Port = 18080,
    [string[]]$RemoteAddress = @('LocalSubnet')
)
$ErrorActionPreference = 'Stop'
$name = "Live Monitor Operations $Port"
if (!(Test-Path Env:SystemRoot)) { throw 'Windows firewall script requires Windows' }
$identity = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$identity.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script from an administrator PowerShell to change Windows Firewall' }
$existing = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($existing) { throw 'Rule already exists; review its address scope before replacing it' }
New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -RemoteAddress $RemoteAddress -Profile Any
