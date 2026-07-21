"""Index the RoboMaster PDFs through the application's real document pipeline.

Use a dedicated Qdrant collection and document IDs so benchmark data never
mixes with user-uploaded knowledge bases.  The output manifest maps offline
Golden evidence hashes to the runtime chunk IDs created by ``/documents``.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from app.api.documents import _process_file
from app.core import vectorstore
from bootstrap_golden_from_mcq import write_json, write_jsonl


def main() -> None:
    args = parse_args()
    document_ids = parse_document_ids(args.document_ids)
    corpus = args.corpus_dir.resolve()
    pdfs = sorted(corpus.glob("*.pdf"))
    if not pdfs:
        raise SystemExit(f"No PDFs found in {corpus}")

    vectorstore.ensure_collection()
    manifest_rows = []
    for index, pdf_path in enumerate(pdfs, start=1):
        key = f"D{index}"
        if key not in document_ids:
            raise SystemExit(f"Missing --document-id {key}=<id> for {pdf_path.name}")
        document_id = document_ids[key]
        if args.reset:
            vectorstore.delete_by_document(document_id)
        result = _process_file(
            document_id=document_id,
            file_type="PDF",
            doc_version=args.doc_version,
            file_path=str(pdf_path),
        )
        for chunk in result.chunks:
            manifest_rows.append({
                "document_key": key,
                "document_id": document_id,
                "document_name": pdf_path.name,
                "doc_version": args.doc_version,
                "runtime_chunk_id": chunk.chunk_id,
                "chunk_index": chunk.chunk_index,
                "content_hash": chunk.content_hash,
                "page_no": chunk.page_no,
                "section_title": chunk.section_title,
            })
        print(f"indexed {key} document_id={document_id} chunks={result.chunk_count}", flush=True)

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    write_jsonl(output_dir / "runtime_chunk_manifest.jsonl", manifest_rows)
    report = {
        "collection": args.collection_hint,
        "doc_version": args.doc_version,
        "document_ids": document_ids,
        "document_count": len(pdfs),
        "chunk_count": len(manifest_rows),
        "manifest": str((output_dir / "runtime_chunk_manifest.jsonl").resolve()),
    }
    write_json(output_dir / "index_report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2))


def parse_document_ids(items: list[str]) -> dict[str, int]:
    parsed = {}
    for item in items:
        key, separator, value = item.partition("=")
        if not separator or not key.strip() or not value.strip().isdigit():
            raise ValueError(f"Invalid --document-id: {item}; expected D1=9001")
        parsed[key.strip()] = int(value.strip())
    return parsed


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--document-id", dest="document_ids", action="append", required=True)
    parser.add_argument("--doc-version", default="benchmark-v1")
    parser.add_argument("--collection-hint", default="robomaster_benchmark_v1")
    parser.add_argument("--reset", action="store_true", help="Delete only the supplied benchmark document IDs before indexing.")
    return parser.parse_args()


if __name__ == "__main__":
    main()
