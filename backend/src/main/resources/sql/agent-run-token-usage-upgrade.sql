ALTER TABLE agent_run
    ADD COLUMN usage_reported TINYINT NOT NULL DEFAULT 0 AFTER source_count,
    ADD COLUMN input_tokens BIGINT NOT NULL DEFAULT 0 AFTER usage_reported,
    ADD COLUMN output_tokens BIGINT NOT NULL DEFAULT 0 AFTER input_tokens,
    ADD COLUMN cached_input_tokens BIGINT NOT NULL DEFAULT 0 AFTER output_tokens,
    ADD COLUMN total_tokens BIGINT NOT NULL DEFAULT 0 AFTER cached_input_tokens,
    ADD COLUMN model_call_count INT NOT NULL DEFAULT 0 AFTER total_tokens;
