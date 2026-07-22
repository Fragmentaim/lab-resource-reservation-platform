-- Run once for databases created before parser-provider quality reporting.
-- The Java service owns this durable audit data; the Python sidecar returns
-- the report as part of a document processing response.

ALTER TABLE kb_document
    ADD COLUMN parser_provider VARCHAR(64) NULL
    COMMENT 'parser provider used for the active version' AFTER chunk_count,
    ADD COLUMN parser_version VARCHAR(32) NULL
    COMMENT 'parser provider version' AFTER parser_provider,
    ADD COLUMN parse_quality TEXT NULL
    COMMENT 'structured parsing quality report JSON' AFTER parser_version;
