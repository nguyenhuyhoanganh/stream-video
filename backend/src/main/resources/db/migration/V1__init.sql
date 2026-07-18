CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    full_name     varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL DEFAULT 'USER',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY,
    user_id    uuid NOT NULL REFERENCES users (id),
    token_hash varchar(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

CREATE TABLE meetings (
    id                 uuid PRIMARY KEY,
    code               varchar(20)  NOT NULL UNIQUE,
    title              varchar(255) NOT NULL,
    description        text,
    host_id            uuid NOT NULL REFERENCES users (id),
    scheduled_start_at timestamptz  NOT NULL,
    scheduled_end_at   timestamptz,
    status             varchar(20)  NOT NULL DEFAULT 'SCHEDULED',
    room_type          varchar(20)  NOT NULL DEFAULT 'MEETING',
    allow_recording    boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_meetings_host ON meetings (host_id, scheduled_start_at DESC);
