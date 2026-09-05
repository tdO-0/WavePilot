CREATE TABLE experiment_job (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    spec_json JSON NOT NULL,
    plan_json JSON NOT NULL,
    generic_spec BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(32) NOT NULL,
    progress JSON NOT NULL,
    external_job_id VARCHAR(128) NULL,
    source_job_id VARCHAR(100) NULL,
    failure_reason TEXT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uk_experiment_job_id (job_id),
    -- Multiple NULLs are allowed; non-NULL keys are unique and case-sensitive.
    UNIQUE KEY uk_experiment_idempotency (idempotency_key),
    KEY ix_experiment_status_created (status, created_at)
);
