# Local GPU reranker

This is a deliberately small, local-only adapter around the already downloaded
`Qwen3-Reranker-0.6B` model. It exposes the OpenAI-compatible endpoint expected
by `lab-knowledge-assistant`:

```text
POST http://127.0.0.1:8011/v1/rerank
GET  http://127.0.0.1:8011/healthz
```

Run it from PowerShell:

```powershell
cd scripts/local-reranker-service
.\start-local-reranker.ps1
```

In a second PowerShell window, run the deterministic smoke test:

```powershell
.\smoke-test.ps1
```

The launcher uses the existing D: package/cache/model locations and requires an
NVIDIA CUDA-capable PyTorch installation. The service refuses CPU fallback so a
"local GPU reranker" never silently becomes a slow CPU dependency.

It also runs Python with `-s` / `PYTHONNOUSERSITE=1`, preventing accidental C:
user-site packages from mixing with the portable D: dependency directory. Use
the same isolation when starting a local embedding worker.

To let the external knowledge service use it, set its ignored local environment
file to the following non-secret values, then restart that service:

```dotenv
ENABLE_RERANK=true
RERANK_BASE_URL=http://127.0.0.1:8011
RERANK_API_KEY=lab-local-reranker
RERANK_MODEL=Qwen3-Reranker-0.6B
```

The endpoint accepts at most 32 candidates, serializes scoring to protect the
GPU, truncates inputs to bounded lengths, and never logs queries or documents.

`rerank_provider` is reported as `api` by the existing knowledge-service
protocol, but its base URL above is `127.0.0.1`: the actual scorer is this local
Qwen3 GPU process, not a remote rerank provider.
