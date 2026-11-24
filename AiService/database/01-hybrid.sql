-- Execute in the dedicated chat_vectors database after the embedding table exists.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;
CREATE INDEX IF NOT EXISTS chat_embeddings_bm25 ON chat_embeddings
USING bm25 (embedding_id, text) WITH (key_field='embedding_id');
