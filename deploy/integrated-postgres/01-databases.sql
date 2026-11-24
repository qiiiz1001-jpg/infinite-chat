CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_search;
CREATE TABLE IF NOT EXISTS chat_embeddings (embedding_id uuid PRIMARY KEY, embedding vector(1024), text text, metadata json);
CREATE INDEX IF NOT EXISTS chat_embeddings_bm25 ON chat_embeddings USING bm25 (embedding_id,text) WITH (key_field='embedding_id');
