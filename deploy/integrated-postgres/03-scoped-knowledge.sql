-- 在 PGVECTOR_DATABASE 对应的 PostgreSQL 库执行；不是 MySQL 脚本。
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS kb_document (
    document_id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    scope VARCHAR(16) NOT NULL CHECK (scope IN ('PERSONAL','SESSION')),
    session_id BIGINT NOT NULL DEFAULT 0,
    filename VARCHAR(200) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    parsed_text TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','INDEXING','READY','FAILED','DELETED')),
    chunk_count INT NOT NULL DEFAULT 0,
    attempts INT NOT NULL DEFAULT 0,
    lease_token VARCHAR(36) NOT NULL DEFAULT '',
    lease_until BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CHECK ((scope='PERSONAL' AND session_id=0) OR (scope='SESSION' AND session_id>0))
);
CREATE UNIQUE INDEX IF NOT EXISTS kb_document_upload_unique
    ON kb_document(owner_id,scope,session_id,sha256) WHERE status <> 'DELETED';
CREATE INDEX IF NOT EXISTS kb_document_space ON kb_document(scope,session_id,status);
CREATE INDEX IF NOT EXISTS kb_document_owner ON kb_document(owner_id,status);
CREATE TABLE IF NOT EXISTS kb_chunk (
    chunk_id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL REFERENCES kb_document(document_id),
    chunk_no INT NOT NULL,
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    UNIQUE(document_id,chunk_no)
);
CREATE INDEX IF NOT EXISTS kb_chunk_document ON kb_chunk(document_id);
-- pg_search 可选；基础 pgvector 环境使用相同权限条件的关键词子串查询降级。
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname='pg_search') THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS kb_chunk_bm25 ON kb_chunk USING bm25 (chunk_id,content) WITH (key_field=''chunk_id'')';
    END IF;
END $$;
