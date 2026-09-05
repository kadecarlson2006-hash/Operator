# Memory schema v1 (Milestone 5)

Migration: `backend/src/main/resources/db/migration/V2__memory_schema.sql`. Design: ADR-019.

```
users ──< organizations ──< people
  │            │              │
  │            └──< projects  │
  │                   │       │
  └──< memories ──────┴───────┘   (person_id / organization_id / project_id, all nullable, ON DELETE SET NULL)
         │  1:1
         ├── memory_embeddings (model, dimensions, vector)      ON DELETE CASCADE
         └── memory_events     (append-only, survives deletes)   no FK
users ──< conversation_sessions (prompt_version, mode, wit, summary)   populated from Milestone 6
```

## memories

| Column | Type | Notes |
|---|---|---|
| id | uuid pk | |
| user_id | uuid fk users | single default user until auth (ADR-021) |
| memory_type | text, check | PERSONAL_PROFILE, PERSON, ORGANIZATION, WORK_FACT, PROJECT, PREFERENCE, GOAL, DECISION, COMMITMENT, EPISODIC_EVENT, SESSION_MEMORY, LONG_TERM_MEMORY |
| content | text, non-blank | the memory itself |
| source_type | text | EXPLICIT_USER, AMBIENT, IMPORT, DEMO, SYSTEM |
| source_reference | text | session id, transcript segment, import file, `demo:<key>` |
| created_at / updated_at / last_used_at | timestamptz | `last_used_at` set by `/touch` (retrieval, Milestone 7) |
| importance / confidence | real 0..1 | search orders by importance; mark-incorrect sets confidence 0 |
| expires_at | timestamptz null | expired rows hidden from search unless `includeExpired` |
| person_id / organization_id / project_id | uuid fk null | structured links |
| privacy_scope | text, check | PERSONAL, WORK, SESSION, PRIVATE, RESTRICTED |
| is_active | bool | disabled memories are kept for inspection, hidden from search |
| metadata | jsonb | small type-specific extras only; never the whole memory |

Indexes: (user_id, is_active), (user_id, memory_type), person_id, project_id, partial on
expires_at, GIN tsvector on content (reserved for Milestone 7 lexical search; v1 uses ILIKE).

## Duplicate rule

`create` refuses a second *active* memory with the same type and normalized content
(trimmed, lower-cased, whitespace-collapsed) and returns 409 with the existing id. Disabled or
marked-incorrect twins do not block a fresh memory.

## Events

CREATED, UPDATED, USED, DISABLED, ENABLED, MARKED_INCORRECT, DELETED, EMBEDDED — one row each,
with a small `details` object (source type, reason, embedding model/dimensions).

## Not yet

Embedding generation, semantic retrieval and ranking, permission policies per mode, explicit
"remember that…" parsing (all Milestone 7). Authentication (later).
