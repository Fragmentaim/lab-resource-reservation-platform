param(
    [ValidateSet('embedding', 'reranker', 'both')]
    [string]$Target = 'both',
    [string]$ModelRoot = (Join-Path $PSScriptRoot '..\.model-cache'),
    [string]$PythonExe = 'python',
    [string]$ProxyUrl = '',
    [int]$MinimumFreeGb = 10
)

$ErrorActionPreference = 'Stop'

$modelDrive = (Split-Path -Qualifier ([System.IO.Path]::GetFullPath($ModelRoot))).TrimEnd(':')
$modelFreeGb = [math]::Floor((Get-PSDrive $modelDrive).Free / 1GB)
if ($modelFreeGb -lt $MinimumFreeGb) {
    throw "模型目录所在磁盘仅剩 $modelFreeGb GB，不足以安全下载模型。"
}

$env:HF_HOME = Join-Path $ModelRoot 'huggingface-home'
$env:HF_HUB_CACHE = Join-Path $ModelRoot 'huggingface'
$env:HF_XET_CACHE = Join-Path $ModelRoot 'xet'
$env:HF_HUB_DISABLE_XET = '1'
$env:TEMP = Join-Path $ModelRoot 'tmp'
$env:TMP = $env:TEMP
$env:PIP_CACHE_DIR = Join-Path $ModelRoot 'pip-cache'
if ($ProxyUrl) {
    $env:HTTP_PROXY = $ProxyUrl
    $env:HTTPS_PROXY = $ProxyUrl
    $env:http_proxy = $ProxyUrl
    $env:https_proxy = $ProxyUrl
}

@($env:HF_HOME, $env:HF_HUB_CACHE, $env:HF_XET_CACHE, $env:TEMP, $env:PIP_CACHE_DIR, (Join-Path $ModelRoot 'models')) |
    ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }

$models = @()
if ($Target -in @('embedding', 'both')) {
    $models += @{ Id = 'Qwen/Qwen3-Embedding-0.6B'; Directory = (Join-Path $ModelRoot 'models\Qwen3-Embedding-0.6B') }
}
if ($Target -in @('reranker', 'both')) {
    $models += @{ Id = 'Qwen/Qwen3-Reranker-0.6B'; Directory = (Join-Path $ModelRoot 'models\Qwen3-Reranker-0.6B') }
}

$modelJson = $models | ForEach-Object { @{ id = $_.Id; directory = $_.Directory } } | ConvertTo-Json -Compress
$modelJsonBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($modelJson))

$pythonCode = @"
import base64
import json
from huggingface_hub import snapshot_download

specs = json.loads(base64.b64decode('$modelJsonBase64'))
if isinstance(specs, dict):
    specs = [specs]

for spec in specs:
    path = snapshot_download(
        repo_id=spec["id"],
        local_dir=spec["directory"],
        ignore_patterns=["model.safetensors"],
    )
    print(f"READY {spec['id']} -> {path}")
"@

$pythonCodeBase64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pythonCode))
$pythonRunner = "exec(__import__('base64').b64decode('$pythonCodeBase64'))"
& $PythonExe -c $pythonRunner
if ($LASTEXITCODE -ne 0) {
    throw "模型配置下载失败。"
}

foreach ($model in $models) {
    $modelFile = Join-Path $model.Directory 'model.safetensors'
    if (Test-Path -LiteralPath $modelFile) {
        Write-Output "WEIGHTS READY $($model.Id) -> $modelFile"
        continue
    }

    $partialFile = "$modelFile.downloading"
    $modelUrl = "https://huggingface.co/$($model.Id)/resolve/main/model.safetensors?download=true"
    $curlArguments = @('--location', '--continue-at', '-', '--retry', '5', '--retry-delay', '3', '--fail', '--output', $partialFile, $modelUrl)
    if ($ProxyUrl) {
        $curlArguments = @('--proxy', $ProxyUrl) + $curlArguments
    }

    & curl.exe @curlArguments
    if ($LASTEXITCODE -ne 0) {
        throw "模型权重下载失败：$($model.Id)。保留可恢复分片：$partialFile"
    }

    Move-Item -LiteralPath $partialFile -Destination $modelFile
    Write-Output "WEIGHTS READY $($model.Id) -> $modelFile"
}
