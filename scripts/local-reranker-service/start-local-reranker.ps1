param(
    [int]$Port = 8011,
    [string]$Python = "python",
    [string]$ModelPath = "Qwen/Qwen3-Reranker-0.6B"
)

$ErrorActionPreference = 'Stop'
if (-not $env:HF_HUB_CACHE) {
    $env:HF_HUB_CACHE = Join-Path $PSScriptRoot '.model-cache'
}
$env:LOCAL_RERANKER_MODEL_PATH = $ModelPath
if (-not $env:LOCAL_RERANKER_API_KEY) {
    $env:LOCAL_RERANKER_API_KEY = 'local-development-key'
}

Set-Location $PSScriptRoot
& $Python -m uvicorn app:app --host 127.0.0.1 --port $Port
