[CmdletBinding()]
param(
    [Parameter(Mandatory)] [long]$ResourceId,
    [Parameter(Mandatory)] [long]$SlotId,
    [Parameter(Mandatory)] [string]$UsersCsv,
    [ValidateRange(1, 5000)] [int]$Threads = 200,
    [string]$TargetBaseUrl = 'http://127.0.0.1:8082',
    [string]$JMeterExecutable = 'D:\apache-jmeter-5.6.3\bin\jmeter.bat'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $JMeterExecutable)) { throw "找不到 JMeter：$JMeterExecutable" }
if (-not (Test-Path -LiteralPath $UsersCsv)) { throw "找不到用户 CSV：$UsersCsv" }

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$plan = Join-Path $repoRoot 'loadtest\jmeter\hot-reservation-burst.jmx'
$runName = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultDir = Join-Path $repoRoot "loadtest\results\$runName"
$reportDir = Join-Path $resultDir 'html-report'
New-Item -ItemType Directory -Path $resultDir -Force | Out-Null

$uri = [Uri]$TargetBaseUrl
$port = if ($uri.IsDefaultPort) { if ($uri.Scheme -eq 'https') { 443 } else { 80 } } else { $uri.Port }

& $JMeterExecutable -n -t $plan -l (Join-Path $resultDir 'result.jtl') -e -o $reportDir `
    "-Jprotocol=$($uri.Scheme)" "-Jhost=$($uri.Host)" "-Jport=$port" `
    "-Jthreads=$Threads" "-Jresource_id=$ResourceId" "-Jslot_id=$SlotId" "-Jusers_csv=$((Resolve-Path $UsersCsv).Path)"
if ($LASTEXITCODE -ne 0) { throw "JMeter 执行失败，退出码：$LASTEXITCODE" }

Write-Host "压测完成：$resultDir" -ForegroundColor Green
