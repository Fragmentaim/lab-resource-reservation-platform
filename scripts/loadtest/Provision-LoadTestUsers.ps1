[CmdletBinding()]
param(
    [ValidateRange(1, 5000)]
    [int]$Count = 200,
    [string]$TargetBaseUrl = 'http://127.0.0.1:8082',
    [string]$OutputCsv,
    [string]$Password = 'LoadTest!2026'
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $OutputCsv) {
    $OutputCsv = Join-Path $repoRoot 'loadtest\runtime\users.csv'
}
New-Item -ItemType Directory -Path (Split-Path -Parent $OutputCsv) -Force | Out-Null

$runId = Get-Date -Format 'yyyyMMddHHmmss'
$users = for ($index = 1; $index -le $Count; $index++) {
    $username = "lt_${runId}_{0:D4}" -f $index
    $body = @{
        username = $username
        password = $Password
        confirmPassword = $Password
        nickname = "压测用户$index"
        phone = "159$('{0:D8}' -f $index)"
    } | ConvertTo-Json -Compress
    try {
        $response = Invoke-RestMethod -Method Post -Uri "$($TargetBaseUrl.TrimEnd('/'))/auth/register" -ContentType 'application/json' -Body $body
        if ($response.code -ne 200) { throw "业务状态码：$($response.code)，$($response.message)" }
    } catch {
        throw "创建压测用户 $username 失败：$($_.Exception.Message)"
    }
    [pscustomobject]@{ username = $username; password = $Password }
}

$users | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding utf8
Write-Host "已创建 $Count 个压测用户：$OutputCsv" -ForegroundColor Green
