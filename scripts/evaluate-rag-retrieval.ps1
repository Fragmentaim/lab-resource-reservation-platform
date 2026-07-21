param(
    [string]$AiBaseUrl = 'http://127.0.0.1:8000/api/v1/ai',
    [int[]]$DocumentIds = @(2),
    [string]$QuestionSetPath = (Join-Path $PSScriptRoot '..\docs\rag-evaluation\question-set.jsonl'),
    [int]$TopK = 5,
    [int]$MaxSamples = 0,
    [int]$RequestTimeoutSeconds = 120,
    [string]$ResultPath = (Join-Path $PSScriptRoot ("..\logs\rag-retrieval-evaluation-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss')))
)

$ErrorActionPreference = 'Stop'
$baseUrl = $AiBaseUrl.TrimEnd('/')
if (-not (Test-Path -LiteralPath $QuestionSetPath)) {
    throw "未找到题集：$QuestionSetPath"
}
if ($TopK -lt 1 -or $TopK -gt 20) {
    throw 'TopK 必须在 1 到 20 之间。'
}
if ($RequestTimeoutSeconds -lt 10 -or $RequestTimeoutSeconds -gt 300) {
    throw 'RequestTimeoutSeconds 必须在 10 到 300 之间。'
}
if (-not $DocumentIds -or $DocumentIds.Count -eq 0) {
    throw '至少要提供一个文档 ID。'
}

$health = Invoke-RestMethod -Uri "$baseUrl/health"
$samples = @(Get-Content -LiteralPath $QuestionSetPath | Where-Object { $_.Trim() } | ForEach-Object { $_ | ConvertFrom-Json })
if ($MaxSamples -gt 0) {
    $samples = @($samples | Select-Object -First $MaxSamples)
}

$results = @()
$sampleIndex = 0
foreach ($sample in $samples) {
    $sampleIndex++
    $startedAt = Get-Date
    try {
        $request = @{
            question = $sample.question
            document_ids = @($DocumentIds)
            top_k = $TopK
        } | ConvertTo-Json -Depth 5 -Compress
        $response = Invoke-RestMethod -Method Post -Uri "$baseUrl/qa/ask" -ContentType 'application/json' -Body $request -TimeoutSec $RequestTimeoutSeconds
        $sources = @($response.sources)
        $sourceSections = @($sources | ForEach-Object { [string]$_.section_title })
        $expectedRank = 0
        if ($sample.answerable -and $sample.expected_section) {
            for ($index = 0; $index -lt $sourceSections.Count; $index++) {
                if ($sourceSections[$index] -eq $sample.expected_section) {
                    $expectedRank = $index + 1
                    break
                }
            }
        }
        $matchedTerms = @($sample.expected_terms | Where-Object { $response.answer -match [regex]::Escape($_) }).Count
        $retrievalSources = @($sources | ForEach-Object { [string]$_.retrieval_source } | Where-Object { $_ } | Sort-Object -Unique)
        $rerankProviders = @($sources | ForEach-Object { [string]$_.rerank_provider } | Where-Object { $_ } | Sort-Object -Unique)
        $isAbstention = -not $sample.answerable -and $response.answer -match '无法|未提及|没有.*信息|抱歉|不能提供'
        $results += [pscustomobject]@{
            id = $sample.id; category = $sample.category; answerable = [bool]$sample.answerable
            expectedSection = $sample.expected_section; expectedTerms = @($sample.expected_terms)
            sourceCount = $sources.Count; sourceSections = $sourceSections
            expectedSectionRank = $expectedRank; expectedSectionHit = ($expectedRank -gt 0)
            matchedTerms = $matchedTerms; retrievalSources = $retrievalSources; rerankProviders = $rerankProviders
            isAbstention = $isAbstention; latencyMs = $response.latency_ms
            requestElapsedMs = [math]::Round(((Get-Date) - $startedAt).TotalMilliseconds, 2)
            status = 'SUCCESS'
        }
        Write-Output ("[{0}/{1}] {2} SUCCESS sectionRank={3} latencyMs={4}" -f $sampleIndex, $samples.Count, $sample.id, $expectedRank, $response.latency_ms)
    } catch {
        $results += [pscustomobject]@{
            id = $sample.id; category = $sample.category; answerable = [bool]$sample.answerable
            expectedSection = $sample.expected_section; status = 'ERROR'; error = $_.Exception.Message
        }
        Write-Output ("[{0}/{1}] {2} ERROR {3}" -f $sampleIndex, $samples.Count, $sample.id, $_.Exception.Message)
    }
}

$successful = @($results | Where-Object status -eq 'SUCCESS')
$answerable = @($successful | Where-Object answerable)
$unanswerable = @($successful | Where-Object { -not $_.answerable })
$latencies = @($successful | ForEach-Object { [double]$_.latencyMs } | Sort-Object)
$p95Index = if ($latencies.Count) { [math]::Min($latencies.Count - 1, [math]::Ceiling($latencies.Count * 0.95) - 1) } else { 0 }

$summary = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    aiBaseUrl = $baseUrl
    health = $health
    documentIds = @($DocumentIds)
    topK = $TopK
    total = $results.Count
    success = $successful.Count
    errors = @($results | Where-Object status -eq 'ERROR').Count
    answerableSectionRecallAtK = if ($answerable.Count) { [math]::Round((@($answerable | Where-Object expectedSectionHit).Count / $answerable.Count), 4) } else { 0 }
    answerableMRR = if ($answerable.Count) { [math]::Round((($answerable | ForEach-Object { if ($_.expectedSectionRank -gt 0) { 1.0 / $_.expectedSectionRank } else { 0 } } | Measure-Object -Sum).Sum / $answerable.Count), 4) } else { 0 }
    answerableTermHitRate = if ($answerable.Count) { [math]::Round((@($answerable | Where-Object { $_.matchedTerms -gt 0 }).Count / $answerable.Count), 4) } else { 0 }
    vectorSourceCoverage = if ($answerable.Count) { [math]::Round((@($answerable | Where-Object { $_.retrievalSources -match 'vector' }).Count / $answerable.Count), 4) } else { 0 }
    rerankCoverage = if ($answerable.Count) { [math]::Round((@($answerable | Where-Object { $_.rerankProviders -match 'api' }).Count / $answerable.Count), 4) } else { 0 }
    unanswerableRefusalRate = if ($unanswerable.Count) { [math]::Round((@($unanswerable | Where-Object isAbstention).Count / $unanswerable.Count), 4) } else { 0 }
    averageLatencyMs = if ($latencies.Count) { [math]::Round((($latencies | Measure-Object -Average).Average), 2) } else { 0 }
    p95LatencyMs = if ($latencies.Count) { $latencies[$p95Index] } else { 0 }
    results = $results
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ResultPath) | Out-Null
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ResultPath -Encoding utf8
Write-Output "RAG retrieval evaluation written to $ResultPath"
Write-Output ("success={0}/{1}, errors={2}, recall@{3}={4}, mrr={5}, vectorCoverage={6}, rerankCoverage={7}, refusalRate={8}, p95Ms={9}" -f $summary.success, $summary.total, $summary.errors, $TopK, $summary.answerableSectionRecallAtK, $summary.answerableMRR, $summary.vectorSourceCoverage, $summary.rerankCoverage, $summary.unanswerableRefusalRate, $summary.p95LatencyMs)
