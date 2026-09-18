ALTER TABLE experiment_job
    ADD COLUMN worker_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN lease_until TIMESTAMP(6) NULL,
    ADD COLUMN heartbeat_at TIMESTAMP(6) NULL,
    ADD COLUMN attempt INT NOT NULL DEFAULT 0,
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0;

-- Lease recovery scans by (status, lease_until); see LeaseRecoveryScanner.
CREATE INDEX ix_experiment_lease_status ON experiment_job (status, lease_until);
