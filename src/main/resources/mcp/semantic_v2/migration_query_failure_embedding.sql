-- Adds vector embedding support for semantic query failure memory.
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE IF EXISTS public.ce_semantic_query_failures
    ADD COLUMN IF NOT EXISTS question_embedding vector;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'ce_semantic_query_failures'
          AND column_name = 'embedding_question'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'ce_semantic_query_failures'
          AND column_name = 'question_embedding'
    ) THEN
        ALTER TABLE public.ce_semantic_query_failures
            RENAME COLUMN embedding_question TO question_embedding;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'ce_semantic_query_failures'
          AND column_name = 'embedding_question'
    ) THEN
        EXECUTE '
            UPDATE public.ce_semantic_query_failures
            SET question_embedding = embedding_question
            WHERE question_embedding IS NULL
        ';
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_ce_semantic_query_failures_embedding
    ON public.ce_semantic_query_failures USING ivfflat (question_embedding vector_cosine_ops) WITH (lists = 100);
