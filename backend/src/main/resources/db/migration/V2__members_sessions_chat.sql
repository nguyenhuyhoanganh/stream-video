CREATE TABLE meeting_members (
    id            uuid PRIMARY KEY,
    meeting_id    uuid NOT NULL REFERENCES meetings (id),
    user_id       uuid REFERENCES users (id),
    invited_email varchar(255),
    role          varchar(20) NOT NULL,
    invited_by    uuid REFERENCES users (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_meeting_member_user UNIQUE (meeting_id, user_id),
    CONSTRAINT chk_member_target CHECK (user_id IS NOT NULL OR invited_email IS NOT NULL)
);
CREATE INDEX idx_members_meeting ON meeting_members (meeting_id);

CREATE TABLE participant_sessions (
    id           uuid PRIMARY KEY,
    meeting_id   uuid NOT NULL REFERENCES meetings (id),
    identity     varchar(100) NOT NULL,
    display_name varchar(255),
    joined_at    timestamptz NOT NULL,
    left_at      timestamptz
);
CREATE INDEX idx_sessions_meeting ON participant_sessions (meeting_id, joined_at);

CREATE TABLE chat_messages (
    id                  uuid PRIMARY KEY,
    meeting_id          uuid NOT NULL REFERENCES meetings (id),
    sender_identity     varchar(100) NOT NULL,
    sender_display_name varchar(255) NOT NULL,
    content             text NOT NULL,
    type                varchar(20) NOT NULL DEFAULT 'TEXT',
    deleted_at          timestamptz,
    deleted_by          varchar(100),
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_meeting_time ON chat_messages (meeting_id, created_at);
