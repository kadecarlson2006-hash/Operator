-- Milestone 14: feedback learning (ADR-045, ADR-046).
--
-- What Operator said, and what the user thought of it. Deliberately NOT the conversation that
-- prompted it: only Operator's own line is kept. Storing the surrounding talk would build a
-- permanent record of other people's conversations, which the brief forbids, and it is not needed
-- for the signal this table exists to carry.

CREATE TABLE comment_feedback (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_id  UUID REFERENCES conversation_sessions(id) ON DELETE SET NULL,
    -- Operator's own words, verbatim.
    comment     TEXT NOT NULL CHECK (length(btrim(comment)) > 0),
    verdict     TEXT NOT NULL CHECK (verdict IN ('HELPFUL', 'UNWANTED', 'WRONG', 'TOO_LATE')),
    trigger     TEXT NOT NULL,
    -- What the model claimed at the time, so a verdict can be read against the scores that let it
    -- through. This is how the confidence and relevance floors finally become measurable.
    confidence  REAL NOT NULL DEFAULT 0,
    relevance   REAL NOT NULL DEFAULT 0,
    category    TEXT,
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every read is "the recent feedback for this user, newest first".
CREATE INDEX comment_feedback_user_recent_idx ON comment_feedback (user_id, created_at DESC);
