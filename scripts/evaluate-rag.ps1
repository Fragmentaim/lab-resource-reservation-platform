param(
    [string]$ApiBaseUrl = 'http://127.0.0.1:8081',
    [Parameter(Mandatory = $true)]
    [string]$Username,
    [Parameter(Mandatory = $true)]
    [string]$Password,
    [string]$QuestionSetPath = (Join-Path $PSScriptRoot '..\docs\rag-evaluation\question-set.jsonl'),
    [string]$ResultPath = (Join-Path $PSScriptRoot ("..\logs\rag-evaluation-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $QuestionSetPath)) {
    throw "未找到题集：$QuestionSetPath"
}

$loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/auth/login" -ContentType 'application/json' -Body $loginBody
if ($login.code -ne 200 -or -not $login.data.token) {
    throw "登录失败：$($login.message)"
}

$headers = @{ Authorization = "Bearer $($login.data.token)" }
$results = @()
Get-Content -LiteralPath $QuestionSetPath | Where-Object { $_.Trim() } | ForEach-Object {
    $sample = $_ | ConvertFrom-Json
    $startedAt = Get-Date
    try {
        $response = Invoke-RestMethod -Method Post -Uri "$ApiBaseUrl/knowledge/qa/ask" -Headers $headers -ContentType 'application/json' -Body (@{ question = $sample.question } | ConvertTo-Json)
        $answer = $response.data.answer
        $hits = @($sample.expected_terms | Where-Object { $answer -match [regex]::Escape($_) }).Count
        $isAbstention = -not $sample.answerable -and $answer -match '无法|未提及|没有.*信息|抱歉'
        $results += [pscustomobject]@{
            id = $sample.id; category = $sample.category; answerable = $sample.answerable
            expectedSection = $sample.expected_section; expectedTerms = @($sample.expected_terms)
            answer = $answer; sourceCount = @($response.data.sources).Count
            matchedTerms = $hits; latencyMs = $response.data.latencyMs
            isAbstention = $isAbstention
            requestElapsedMs = [math]::Round(((Get-Date) - $startedAt).TotalMilliseconds, 2)
            status = 'SUCCESS'
        }
    } catch {
        $results += [pscustomobject]@{
            id = $sample.id; category = $sample.category; answerable = $sample.answerable
            expectedSection = $sample.expected_section; expectedTerms = @($sample.expected_terms)
            status = 'ERROR'; error = $_.Exception.Message
        }
    }
}

$successful = @($results | Where-Object status -eq 'SUCCESS')
$answerableResults = @($successful | Where-Object answerable)
$unanswerableResults = @($successful | Where-Object { -not $_.answerable })
$answerableTermHits = @($answerableResults | Where-Object { $_.matchedTerms -gt 0 }).Count
$answerableWithSources = @($answerableResults | Where-Object { $_.sourceCount -gt 0 }).Count
$unanswerableRefusals = @($unanswerableResults | Where-Object isAbstention).Count

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o'); apiBaseUrl = $ApiBaseUrl
    total = $results.Count; success = $successful.Count
    errors = @($results | Where-Object status -eq 'ERROR').Count
    answerableTermHitRate = if ($answerableResults.Count) { [math]::Round($answerableTermHits / $answerableResults.Count, 4) } else { 0 }
    answerableCitationCoverage = if ($answerableResults.Count) { [math]::Round($answerableWithSources / $answerableResults.Count, 4) } else { 0 }
    unanswerableRefusalRate = if ($unanswerableResults.Count) { [math]::Round($unanswerableRefusals / $unanswerableResults.Count, 4) } else { 0 }
    results = $results
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ResultPath) | Out-Null
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ResultPath -Encoding utf8
Write-Output "RAG evaluation written to $ResultPath"
Write-Output ("success={0}/{1}, errors={2}, termHitRate={3}, citationCoverage={4}, refusalRate={5}" -f $summary.success, $summary.total, $summary.errors, $summary.answerableTermHitRate, $summary.answerableCitationCoverage, $summary.unanswerableRefusalRate)
