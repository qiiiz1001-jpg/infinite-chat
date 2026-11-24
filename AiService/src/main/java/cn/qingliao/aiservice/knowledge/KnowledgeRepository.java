package cn.qingliao.aiservice.knowledge;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import javax.sql.DataSource;

@Repository
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public record Doc(
            String documentId,
            long ownerId,
            String scope,
            long sessionId,
            String filename,
            long byteSize,
            String status,
            int chunkCount,
            int attempts,
            String lastError,
            long createdAt,
            long updatedAt,
            String parsedText,
            String leaseToken) {}

    public record View(
            String documentId,
            String ownerId,
            String scope,
            String sessionId,
            String filename,
            long byteSize,
            String status,
            int chunkCount,
            int attempts,
            String lastError,
            long createdAt,
            long updatedAt) {}

    private final RowMapper<Doc> mapper =
            (r, n) ->
                    new Doc(
                            r.getString("document_id"),
                            r.getLong("owner_id"),
                            r.getString("scope"),
                            r.getLong("session_id"),
                            r.getString("filename"),
                            r.getLong("byte_size"),
                            r.getString("status"),
                            r.getInt("chunk_count"),
                            r.getInt("attempts"),
                            r.getString("last_error"),
                            r.getLong("created_at"),
                            r.getLong("updated_at"),
                            r.getString("parsed_text"),
                            r.getString("lease_token"));

    public KnowledgeRepository(JdbcTemplate jdbc, @Qualifier("pgDataSource") DataSource source) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    public View view(Doc d) {
        return new View(
                d.documentId(),
                Long.toString(d.ownerId()),
                d.scope(),
                Long.toString(d.sessionId()),
                d.filename(),
                d.byteSize(),
                d.status(),
                d.chunkCount(),
                d.attempts(),
                d.lastError(),
                d.createdAt(),
                d.updatedAt());
    }

    public Doc find(String id) {
        return jdbc.query("SELECT * FROM kb_document WHERE document_id=?", mapper, id).stream()
                .findFirst()
                .orElse(null);
    }

    public List<View> list(long user, String scope, long session) {
        return jdbc
                .query(
                        "SELECT * FROM kb_document WHERE scope=? AND status<>'DELETED' AND "
                                + ("PERSONAL".equals(scope) ? "owner_id=?" : "session_id=?")
                                + " ORDER BY created_at DESC,document_id LIMIT 100",
                        mapper,
                        scope,
                        "PERSONAL".equals(scope) ? user : session)
                .stream()
                .map(this::view)
                .toList();
    }

    public Doc create(long user, String scope, long session, KnowledgeParser.Parsed parsed) {
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " kb_document(document_id,owner_id,scope,session_id,filename,sha256,byte_size,parsed_text,status,created_at,updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,'PENDING',?,?)",
                    id,
                    user,
                    scope,
                    session,
                    parsed.filename(),
                    parsed.sha256(),
                    parsed.bytes(),
                    parsed.text(),
                    now,
                    now);
        } catch (DuplicateKeyException duplicate) {
            return jdbc
                    .query(
                            "SELECT * FROM kb_document WHERE owner_id=? AND scope=? AND"
                                    + " session_id=? AND sha256=? AND status<>'DELETED'",
                            mapper,
                            user,
                            scope,
                            session,
                            parsed.sha256())
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> duplicate);
        }
        return find(id);
    }

    public void retry(String id) {
        int changed =
                jdbc.update(
                        "UPDATE kb_document SET"
                                + " status='PENDING',attempts=0,last_error='',updated_at=? WHERE"
                                + " document_id=? AND status='FAILED'",
                        System.currentTimeMillis(),
                        id);
        if (changed != 1) throw KnowledgeAccess.invalid("只有索引失败的文件可以重试");
    }

    /** 删除和索引提交争用同一行锁，旧索引进程不能复活已删除资料。 */
    public void delete(String id) {
        transactions.executeWithoutResult(
                tx -> {
                    jdbc.queryForList(
                            "SELECT document_id FROM kb_document WHERE document_id=? FOR UPDATE",
                            id);
                    jdbc.update(
                            "UPDATE kb_document SET"
                                + " status='DELETED',parsed_text='',lease_token='',lease_until=0,updated_at=?"
                                + " WHERE document_id=?",
                            System.currentTimeMillis(),
                            id);
                    jdbc.update("DELETE FROM kb_chunk WHERE document_id=?", id);
                });
    }

    public void recover() {
        long now = System.currentTimeMillis();
        jdbc.update(
                "UPDATE kb_document SET status=CASE WHEN attempts>=3 THEN 'FAILED' ELSE 'PENDING'"
                    + " END,lease_token='',lease_until=0,last_error='索引执行超时，可核对后重试',updated_at=?"
                    + " WHERE status='INDEXING' AND lease_until<?",
                now,
                now);
    }

    public Doc claim() {
        var pending =
                jdbc.queryForList(
                        "SELECT document_id FROM kb_document WHERE status='PENDING' ORDER BY"
                                + " created_at LIMIT 1",
                        String.class);
        if (pending.isEmpty()) return null;
        String id = pending.get(0), token = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        int changed =
                jdbc.update(
                        "UPDATE kb_document SET"
                            + " status='INDEXING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=?"
                            + " WHERE document_id=? AND status='PENDING'",
                        token,
                        now + 300000,
                        now,
                        id);
        return changed == 1 ? find(id) : null;
    }

    public void heartbeat(Doc doc) {
        if (jdbc.update(
                        "UPDATE kb_document SET lease_until=? WHERE document_id=? AND"
                                + " status='INDEXING' AND lease_token=?",
                        System.currentTimeMillis() + 300000,
                        doc.documentId(),
                        doc.leaseToken())
                != 1) throw new IllegalStateException("资料已删除或索引租约失效");
    }

    public void fail(Doc doc) {
        jdbc.update(
                "UPDATE kb_document SET"
                    + " status='FAILED',last_error='索引失败，请检查Embedding配置、维度或数据库后重试',lease_until=0,updated_at=?"
                    + " WHERE document_id=? AND status='INDEXING' AND lease_token=?",
                System.currentTimeMillis(),
                doc.documentId(),
                doc.leaseToken());
    }

    public void complete(Doc doc, List<String> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) throw new IllegalStateException("向量数量与片段不匹配");
        transactions.executeWithoutResult(
                tx -> {
                    var current =
                            jdbc.query(
                                    "SELECT * FROM kb_document WHERE document_id=? FOR UPDATE",
                                    mapper,
                                    doc.documentId());
                    if (current.isEmpty()
                            || !"INDEXING".equals(current.get(0).status())
                            || !doc.leaseToken().equals(current.get(0).leaseToken())) return;
                    jdbc.update("DELETE FROM kb_chunk WHERE document_id=?", doc.documentId());
                    for (int i = 0; i < chunks.size(); i++)
                        jdbc.update(
                                "INSERT INTO"
                                    + " kb_chunk(chunk_id,document_id,chunk_no,content,embedding)"
                                    + " VALUES(?,?,?,?,CAST(? AS vector))",
                                UUID.randomUUID().toString(),
                                doc.documentId(),
                                i + 1,
                                chunks.get(i),
                                vector(vectors.get(i)));
                    jdbc.update(
                            "UPDATE kb_document SET"
                                + " status='READY',chunk_count=?,last_error='',lease_until=0,updated_at=?"
                                + " WHERE document_id=?",
                            chunks.size(),
                            System.currentTimeMillis(),
                            doc.documentId());
                });
    }

    private static final String FROM =
            " FROM kb_chunk c JOIN kb_document d ON d.document_id=c.document_id ";
    private static final String ACCESS =
            " d.status='READY' AND ((d.scope='SESSION' AND d.session_id=?) OR (d.scope='PERSONAL'"
                    + " AND d.owner_id=? AND ?)) ";
    private static final String SELECT =
            "SELECT c.content,c.chunk_no,d.document_id,d.filename,d.scope ";

    private Content content(java.sql.ResultSet r, int row) throws java.sql.SQLException {
        String id = r.getString("document_id"), name = r.getString("filename");
        int number = r.getInt("chunk_no");
        Metadata metadata = new Metadata();
        metadata.put("document_id", id);
        metadata.put("file_name", name);
        metadata.put("chunk_no", number);
        metadata.put("scope", r.getString("scope"));
        return Content.from(
                TextSegment.from(
                        "[资料："
                                + name
                                + "；documentId="
                                + id
                                + "；片段="
                                + number
                                + "]\n"
                                + r.getString("content"),
                        metadata));
    }

    /** 两路召回在SQL内部限定可见范围，再排序/截断，禁止召回后才过滤。 */
    public List<Content> vectors(KnowledgeAccess.Context context, float[] query, int limit) {
        return jdbc.query(
                SELECT
                        + FROM
                        + " WHERE "
                        + ACCESS
                        + " ORDER BY c.embedding <=> CAST(? AS vector),c.chunk_id LIMIT ?",
                this::content,
                context.sessionId(),
                context.userId(),
                context.personal(),
                vector(query),
                limit);
    }

    public List<Content> keywords(KnowledgeAccess.Context context, String query, int limit) {
        try {
            return jdbc.query(
                    SELECT
                            + ",paradedb.score(c.chunk_id) AS relevance "
                            + FROM
                            + " WHERE "
                            + ACCESS
                            + " AND c.chunk_id @@@ paradedb.match('content', ?) ORDER BY relevance"
                            + " DESC,c.chunk_id LIMIT ?",
                    this::content,
                    context.sessionId(),
                    context.userId(),
                    context.personal(),
                    query,
                    limit);
        } catch (DataAccessException unavailable) {
            String like =
                    "%"
                            + query.toLowerCase(Locale.ROOT)
                                    .replace("!", "!!")
                                    .replace("%", "!%")
                                    .replace("_", "!_")
                            + "%";
            return jdbc.query(
                    SELECT
                            + FROM
                            + " WHERE "
                            + ACCESS
                            + " AND LOWER(c.content) LIKE ? ESCAPE '!' ORDER BY c.chunk_id LIMIT ?",
                    this::content,
                    context.sessionId(),
                    context.userId(),
                    context.personal(),
                    like,
                    limit);
        }
    }

    /** 新资料集使用新记忆键，删除/索引变化后不继续携带旧回答与工具结果。 */
    public String memoryRevision(KnowledgeAccess.Context context) {
        var rows =
                jdbc.queryForList(
                        "SELECT d.document_id,d.updated_at FROM kb_document d WHERE "
                                + ACCESS
                                + " ORDER BY d.document_id",
                        context.sessionId(),
                        context.userId(),
                        context.personal());
        return UUID.nameUUIDFromBytes(
                        rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    public static String vector(float[] values) {
        if (values == null || values.length != 1024)
            throw new IllegalArgumentException("Embedding维度必须是1024");
        for (float f : values)
            if (!Float.isFinite(f)) throw new IllegalArgumentException("Embedding包含非法数值");
        return Arrays.toString(values);
    }
}
