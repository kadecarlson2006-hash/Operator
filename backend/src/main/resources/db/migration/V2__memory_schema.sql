-- Milestone 5: memory database v1 (ADR-019).
-- One generalized `memories` table with a typed enum + structured links, plus normalized
-- people / organizations / projects, vector embeddings, and an append-only event log.

CREATE TABLE users (
    id           UUID PRIMARY KEY,
    handle       TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Single-user until authentication lands (brief: "design authentication later").
INSERT INTO users (id, handle, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'default', 'Operator user');

CREATE TABLE organizations (
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    aliases    TEXT[] NOT NULL DEFAULT '{}',
    notes      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active  BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX organizations_user_name_idx ON organizations (user_id, lower(name));

CREATE TABLE people (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    aliases         TEXT[] NOT NULL DEFAULT '{}',
    relationship    TEXT,
    organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    role            TEXT,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX people_user_name_idx ON people (user_id, lower(name));

CREATE TABLE projects (
    id              UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    description     TEXT,
    organization_id UUID REFERENCES organizations(id) ON DELETE SET NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX projects_user_name_idx ON projects (user_id, lower(name));

CREATE TABLE memories (
    id               UUID PRIMARY KEY,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    memory_type      TEXT NOT NULL CHECK (memory_type IN (
                        'PERSONAL_PROFILE','PERSON','ORGANIZATION','WORK_FACT','PROJECT','PREFERENCE',
                        'GOAL','DECISION','COMMITMENT','EPISODIC_EVENT','SESSION_MEMORY','LONG_TERM_MEMORY')),
    content          TEXT NOT NULL CHECK (length(btrim(content)) > 0),
    source_type      TEXT NOT NULL,            -- EXPLICIT_USER, AMBIENT, IMPORT, DEMO, SYSTEM
    source_reference TEXT,                     -- session id, transcript segment, import file, demo key
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at     TIMESTAMPTZ,
    importance       REAL NOT NULL DEFAULT 0.5 CHECK (importance BETWEEN 0 AND 1),
    confidence       REAL NOT NULL DEFAULT 0.5 CHECK (confidence BETWEEN 0 AND 1),
    expires_at       TIMESTAMPTZ,
    person_id        UUID REFERENCES people(id) ON DELETE SET NULL,
    organization_id  UUID REFERENCES organizations(id) ON DELETE SET NULL,
    project_id       UUID REFERENCES projects(id) ON DELETE SET NULL,
    privacy_scope    TEXT NOT NULL DEFAULT 'PERSONAL' CHECK (privacy_scope IN ('PERSONAL','WORK','SESSION','PRIVATE','RESTRICTED')),
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    metadata         JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX memories_user_active_idx   ON memories (user_id, is_active);
CREATE INDEX memories_user_type_idx     ON memories (user_id, memory_type);
CREATE INDEX memories_person_idx        ON memories (person_id);
CREATE INDEX memories_project_idx       ON memories (project_id);
CREATE INDEX memories_expires_idx       ON memories (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX memories_content_trgm_idx  ON memories USING gin (to_tsvector('english', content));

-- One embedding per memory. The column is dimension-agnostic until the embedding model is chosen
-- (Milestone 7); an HNSW index needs a fixed dimension and is added by a later migration.
CREATE TABLE memory_embeddings (
    memory_id  UUID PRIMARY KEY REFERENCES memories(id) ON DELETE CASCADE,
    model      TEXT NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    embedding  vector NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only provenance / audit trail ("the user must be able to inspect what Operator believes").
CREATE TABLE memory_events (
    id          BIGSERIAL PRIMARY KEY,
    memory_id   UUID NOT NULL,                 -- not a FK: events outlive hard deletes
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type  TEXT NOT NULL CHECK (event_type IN ('CREATED','UPDATED','USED','DISABLED','ENABLED','MARKED_INCORRECT','DELETED','EMBEDDED')),
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    details     JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX memory_events_memory_idx ON memory_events (memory_id, at);

-- Sessions record which prompt version produced which behaviour (brief: "record which prompt
-- version was used for a session"). Populated from Milestone 6 onward.
CREATE TABLE conversation_sessions (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at       TIMESTAMPTZ,
    operator_mode  TEXT,
    wit_level      TEXT,
    prompt_version TEXT,
    summary        TEXT
);
