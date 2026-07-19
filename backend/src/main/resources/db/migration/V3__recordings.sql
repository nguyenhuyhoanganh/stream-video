CREATE TABLE recordings (
    id               uuid PRIMARY KEY,
    meeting_id       uuid NOT NULL REFERENCES meetings (id),
    egress_id        varchar(100) NOT NULL UNIQUE,
    status           varchar(20) NOT NULL DEFAULT 'STARTING',
    s3_key           varchar(500),
    duration_seconds bigint,
    size_bytes       bigint,
    started_by       uuid REFERENCES users (id),
    started_at       timestamptz NOT NULL DEFAULT now(),
    ended_at         timestamptz
);
CREATE INDEX idx_recordings_meeting ON recordings (meeting_id, started_at DESC);
