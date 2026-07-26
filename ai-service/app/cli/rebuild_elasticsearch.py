"""Rebuild the Elasticsearch lexical index from Qdrant payloads."""

from app.config import settings
from app.core import elasticsearch_store, vectorstore


def main() -> None:
    elasticsearch_store.ensure_index()
    indexed = elasticsearch_store.index_payloads(vectorstore.iter_chunk_payloads())
    print(
        f"Indexed {indexed} chunks into Elasticsearch index "
        f"{settings.elasticsearch_index}."
    )


if __name__ == "__main__":
    main()
