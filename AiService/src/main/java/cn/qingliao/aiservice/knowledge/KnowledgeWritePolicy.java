package cn.qingliao.aiservice.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/** 共享知识库写入采用显式维护者名单；不由模型或客户端自行授予权限。 */
@Component
public class KnowledgeWritePolicy {
    @Value("${rag.maintainer-ids:}")
    private String maintainerIds;

    public boolean allowed(Long userId) {
        return userId != null
                && Arrays.stream(maintainerIds.split(","))
                        .map(String::strip)
                        .anyMatch(userId.toString()::equals);
    }
}
