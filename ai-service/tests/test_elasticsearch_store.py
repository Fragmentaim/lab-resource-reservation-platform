from app.core import elasticsearch_store


class _FakeIndices:
    def exists(self, **_kwargs):
        return True


class _CreateRaceIndices:
    def exists(self, **_kwargs):
        return False

    def create(self, **_kwargs):
        raise elasticsearch_store.BadRequestError(
            message="resource_already_exists_exception",
            meta=type("Meta", (), {"status": 400})(),
            body={"error": {"type": "resource_already_exists_exception"}},
        )


class _FakeClient:
    def __init__(self):
        self.indices = _FakeIndices()
        self.search_request = None
        self.delete_request = None

    def search(self, **kwargs):
        self.search_request = kwargs
        return {
            "hits": {
                "hits": [{
                    "_id": "chunk-1",
                    "_score": 7.5,
                    "_source": {
                        "document_id": 12,
                        "doc_version": "v2",
                        "chunk_id": "chunk-1",
                        "chunk_index": 3,
                        "content": "雷达站定位规则",
                        "page_no": 8,
                        "section_title": "定位",
                        "title_path": ["规则", "定位"],
                    },
                }]
            }
        }

    def delete_by_query(self, **kwargs):
        self.delete_request = kwargs
        return {"deleted": 4}


def test_elasticsearch_search_pushes_acl_filter_and_preserves_payload(monkeypatch):
    client = _FakeClient()
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: client)
    monkeypatch.setattr(elasticsearch_store, "ensure_index", lambda: None)

    results = elasticsearch_store.search(
        query="雷达站定位规则",
        top_k=5,
        document_ids=[12, 18],
    )

    assert client.search_request["size"] == 5
    assert client.search_request["query"]["bool"]["filter"] == [
        {"terms": {"document_id": [12, 18]}}
    ]
    assert results[0]["chunk_id"] == "chunk-1"
    assert results[0]["document_id"] == 12
    assert results[0]["lexical_score"] == 7.5


def test_ensure_index_treats_concurrent_create_as_success(monkeypatch):
    client = _FakeClient()
    client.indices = _CreateRaceIndices()
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: client)

    elasticsearch_store.ensure_index()


def test_elasticsearch_search_rejects_empty_acl_without_querying(monkeypatch):
    client = _FakeClient()
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: client)

    assert elasticsearch_store.search("规则", top_k=5, document_ids=[]) == []
    assert client.search_request is None


def test_elasticsearch_delete_uses_document_filter(monkeypatch):
    client = _FakeClient()
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: client)
    monkeypatch.setattr(elasticsearch_store, "ensure_index", lambda: None)

    deleted = elasticsearch_store.delete_by_document(12)

    assert deleted == 4
    assert client.delete_request["query"] == {"term": {"document_id": 12}}


def test_elasticsearch_search_filters_each_document_by_active_version(monkeypatch):
    client = _FakeClient()
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: client)
    monkeypatch.setattr(elasticsearch_store, "ensure_index", lambda: None)

    elasticsearch_store.search(
        query="规则",
        top_k=5,
        document_ids=[12, 18],
        document_versions={12: "v1", 18: "v3"},
    )

    scopes = client.search_request["query"]["bool"]["filter"][0]["bool"]
    assert scopes["minimum_should_match"] == 1
    assert scopes["should"] == [
        {"bool": {"must": [
            {"term": {"document_id": 12}},
            {"term": {"doc_version": "v1"}},
        ]}},
        {"bool": {"must": [
            {"term": {"document_id": 18}},
            {"term": {"doc_version": "v3"}},
        ]}},
    ]


def test_elasticsearch_delete_can_target_one_document_version(monkeypatch):
    client = _FakeClient()
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: client)
    monkeypatch.setattr(elasticsearch_store, "ensure_index", lambda: None)

    elasticsearch_store.delete_by_document_version(12, "v2")

    assert client.delete_request["query"] == {
        "bool": {
            "must": [
                {"term": {"document_id": 12}},
                {"term": {"doc_version": "v2"}},
            ]
        }
    }


def test_migration_skips_invalid_payloads_and_uses_stable_chunk_ids(monkeypatch):
    captured = {}

    def fake_bulk(_client, actions, **kwargs):
        captured["actions"] = list(actions)
        captured["kwargs"] = kwargs
        return len(captured["actions"]), []

    monkeypatch.setattr(elasticsearch_store, "ensure_index", lambda: None)
    monkeypatch.setattr(elasticsearch_store, "get_client", lambda: object())
    monkeypatch.setattr(elasticsearch_store.helpers, "bulk", fake_bulk)

    indexed = elasticsearch_store.index_payloads([
        {
            "document_id": 12,
            "doc_version": "v1",
            "chunk_id": "chunk-12-1",
            "content": "比赛规则正文",
            "title_path": ["规则", "赛制"],
        },
        {"document_id": 12, "chunk_id": "", "content": "无效数据"},
    ])

    assert indexed == 1
    assert captured["actions"][0]["_id"] == "chunk-12-1"
    assert captured["actions"][0]["_source"]["title_path_text"] == "规则 > 赛制"
