param(
    [int]$Port = 8011
)

$ErrorActionPreference = 'Stop'
$baseUrl = "http://127.0.0.1:$Port"
$health = Invoke-RestMethod -Uri "$baseUrl/healthz"
if (-not $health.cuda_available) {
    throw 'The local reranker cannot see CUDA.'
}

$body = @{
    model = 'Qwen3-Reranker-0.6B'
    query = '我想查看明天实验室预约是否可用'
    documents = @(
        '实验室预约：可查看明日空闲时段并提交预约申请。'
        '图书馆借阅：可续借图书并查看逾期记录。'
    )
    top_n = 2
    return_documents = $false
} | ConvertTo-Json -Depth 4 -Compress

$result = Invoke-RestMethod -Method Post -Uri "$baseUrl/v1/rerank" `
    -Headers @{ Authorization = 'Bearer lab-local-reranker' } `
    -ContentType 'application/json' -Body $body

if ($result.results.Count -ne 2 -or $result.results[0].index -ne 0) {
    throw 'The relevant laboratory reservation candidate did not rank first.'
}

"PASS: CUDA=$($health.cuda_available), top_index=$($result.results[0].index), score=$($result.results[0].relevance_score)"
