package cn.qingliao.aiservice.knowledge;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeService {
    private final KnowledgeAccess access;
    private final KnowledgeRepository repo;
    private final KnowledgeParser parser;

    public KnowledgeRepository.View upload(
            long user, String scope, Long session, MultipartFile file) {
        long target = access.space(user, scope, session);
        var parsed = parser.parse(file);
        // 解析可能耗时；落库前再检查成员，防止沿用解析前的权限。
        access.space(user, scope, session);
        return repo.view(repo.create(user, scope, target, parsed));
    }

    public List<KnowledgeRepository.View> list(long user, String scope, Long session) {
        long target = access.space(user, scope, session);
        return repo.list(user, scope, target);
    }

    public KnowledgeRepository.Doc readable(long user, String id) {
        var doc = repo.find(id);
        if (doc == null || "DELETED".equals(doc.status())) throw KnowledgeAccess.denied();
        if ("PERSONAL".equals(doc.scope())) {
            if (doc.ownerId() != user) throw KnowledgeAccess.denied();
        } else access.context(user, doc.sessionId());
        return doc;
    }

    public KnowledgeRepository.View get(long user, String id) {
        return repo.view(readable(user, id));
    }

    public String text(long user, String id) {
        return readable(user, id).parsedText();
    }

    public void delete(long user, String id) {
        var doc = readable(user, id);
        if (doc.ownerId() != user) throw KnowledgeAccess.denied();
        repo.delete(id);
    }

    public KnowledgeRepository.View retry(long user, String id) {
        var doc = readable(user, id);
        if (doc.ownerId() != user) throw KnowledgeAccess.denied();
        repo.retry(id);
        return repo.view(repo.find(id));
    }
}
