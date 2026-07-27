import asyncio
from pathlib import Path

from app.api import documents
from app.models.schemas import ProcessByUrlRequest, ProcessResponse


def test_process_by_url_uses_shared_pipeline_and_deletes_temp_file(monkeypatch, tmp_path):
    downloaded = tmp_path / "downloaded.pdf"
    downloaded.write_bytes(b"%PDF-test")
    observed = {}

    async def fake_download(file_url, file_type):
        observed["url"] = file_url
        observed["file_type"] = file_type
        return str(downloaded)

    def fake_process(document_id, file_type, doc_version, file_path):
        observed["document_id"] = document_id
        observed["doc_version"] = doc_version
        observed["file_path"] = file_path
        assert Path(file_path).exists()
        return ProcessResponse(
            document_id=document_id,
            doc_version=doc_version,
            chunk_count=0,
            chunk_ids=[],
            vector_ids=[],
            chunks=[],
            status="READY",
        )

    monkeypatch.setattr(documents, "_download_to_temp", fake_download)
    monkeypatch.setattr(documents, "_process_file", fake_process)

    response = asyncio.run(documents.process_document_by_url(ProcessByUrlRequest(
        document_id=42,
        file_url="http://minio:9000/lab-knowledge/document.pdf?signature=test",
        file_name="document.pdf",
        file_type="PDF",
        doc_version="v2",
    )))

    assert response.document_id == 42
    assert response.doc_version == "v2"
    assert observed["url"].startswith("http://minio:9000/")
    assert observed["file_type"] == "PDF"
    assert observed["file_path"] == str(downloaded)
    assert not downloaded.exists()


def test_safe_suffix_removes_path_characters():
    assert documents._safe_suffix("../../PDF") == "pdf"
    assert documents._safe_suffix("") == "bin"
