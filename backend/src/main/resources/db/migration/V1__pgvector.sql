-- Milestone 4: database connection setup. The memory schema arrives in Milestone 5.
-- pgvector must be available in the PostgreSQL image (docker-compose uses pgvector/pgvector).
CREATE EXTENSION IF NOT EXISTS vector;
