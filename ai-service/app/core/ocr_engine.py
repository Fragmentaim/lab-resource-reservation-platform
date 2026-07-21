"""Lazy, GPU-backed OCR adapter used by the document parser providers."""

from __future__ import annotations

from dataclasses import dataclass
import os
from threading import Lock
from typing import Any

import numpy as np

from app.config import settings


@dataclass(frozen=True)
class OcrTextResult:
    text: str
    average_confidence: float | None
    line_count: int


class PaddleOcrEngine:
    provider = "paddleocr-pp-ocrv5"
    version = "3.3.0"

    _instance: "PaddleOcrEngine | None" = None
    _lock = Lock()

    @classmethod
    def shared(cls) -> "PaddleOcrEngine":
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls()
            return cls._instance

    def __init__(self) -> None:
        if not settings.ocr_enabled or settings.ocr_provider.lower() != "paddle":
            raise RuntimeError("OCR is disabled or no supported OCR provider is configured")

        os.environ.setdefault("PADDLE_PDX_CACHE_HOME", settings.ocr_model_cache_dir)
        os.environ.setdefault("PADDLE_HOME", "./.model-cache/paddle")
        os.environ.setdefault("DISABLE_MODEL_SOURCE_CHECK", "True")
        import paddle
        from paddleocr import PaddleOCR

        if not paddle.is_compiled_with_cuda() or paddle.device.cuda.device_count() < 1:
            raise RuntimeError("PaddleOCR GPU runtime is unavailable")
        self._ocr = PaddleOCR(
            lang=settings.ocr_language,
            ocr_version="PP-OCRv5",
            device=settings.ocr_device,
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            use_textline_orientation=False,
        )

    def recognize(self, image: np.ndarray) -> OcrTextResult:
        results = self._ocr.predict(image)
        texts: list[str] = []
        scores: list[float] = []
        for result in results:
            for text in result.get("rec_texts", []) or []:
                normalized = str(text).strip()
                if normalized:
                    texts.append(normalized)
            for score in result.get("rec_scores", []) or []:
                try:
                    scores.append(float(score))
                except (TypeError, ValueError):
                    continue
        return OcrTextResult(
            text="\n".join(texts),
            average_confidence=round(sum(scores) / len(scores), 4) if scores else None,
            line_count=len(texts),
        )
