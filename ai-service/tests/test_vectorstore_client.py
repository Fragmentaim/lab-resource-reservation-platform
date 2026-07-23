from types import SimpleNamespace

import pytest

from app.core import vectorstore


def test_remote_qdrant_failure_is_not_silently_replaced_with_local_storage(monkeypatch):
    class UnavailableRemoteClient:
        def __init__(self, **kwargs):
            assert kwargs == {"url": "http://qdrant.internal:6333"}

        def get_collections(self):
            raise ConnectionError("qdrant unavailable")

    monkeypatch.setattr(vectorstore, "_client", None)
    monkeypatch.setattr(
        vectorstore,
        "settings",
        SimpleNamespace(qdrant_url="http://qdrant.internal:6333", qdrant_local_path=""),
    )
    monkeypatch.setattr(vectorstore, "QdrantClient", UnavailableRemoteClient)

    with pytest.raises(ConnectionError, match="qdrant unavailable"):
        vectorstore.get_client()

    assert vectorstore._client is None


def test_local_qdrant_requires_an_explicit_storage_path(monkeypatch, tmp_path):
    created_with = {}

    class LocalClient:
        def __init__(self, **kwargs):
            created_with.update(kwargs)

    local_path = tmp_path / "nested" / "qdrant"
    monkeypatch.setattr(vectorstore, "_client", None)
    monkeypatch.setattr(
        vectorstore,
        "settings",
        SimpleNamespace(qdrant_url="http://unused:6333", qdrant_local_path=str(local_path)),
    )
    monkeypatch.setattr(vectorstore, "QdrantClient", LocalClient)

    client = vectorstore.get_client()

    assert isinstance(client, LocalClient)
    assert created_with == {"path": str(local_path)}
    assert local_path.is_dir()
