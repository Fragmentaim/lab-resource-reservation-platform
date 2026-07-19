param(
    [int]$Port = 8011
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$python = 'C:\Users\l\AppData\Local\Programs\Python\Python312\python.exe'

if (-not (Test-Path -LiteralPath $python)) {
    throw "Python 3.12 was not found at $python"
}

$env:PYTHONPATH = 'D:\python-packages'
$env:PYTHONNOUSERSITE = '1'
$env:HF_HUB_CACHE = 'D:\AI-Models\huggingface'
$env:TRANSFORMERS_CACHE = 'D:\AI-Models\huggingface'
$env:LOCAL_RERANKER_MODEL_PATH = 'D:\AI-Models\models\Qwen3-Reranker-0.6B'
$env:LOCAL_RERANKER_API_KEY = 'lab-local-reranker'

Set-Location $PSScriptRoot
& $python -s -m uvicorn app:app --host 127.0.0.1 --port $Port
