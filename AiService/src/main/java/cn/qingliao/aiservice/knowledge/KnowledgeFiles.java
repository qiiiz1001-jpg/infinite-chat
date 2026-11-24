package cn.qingliao.aiservice.knowledge;

import java.io.IOException;
import java.nio.file.*;

/** 只允许向配置目录中的 Markdown 文件追加，模型工具参数也不能决定任意文件路径。 */
public final class KnowledgeFiles {
    private KnowledgeFiles() {}

    public static synchronized void append(String directory, String requestedName, String content)
            throws IOException {
        String name =
                requestedName == null || requestedName.isBlank() ? "Qingliao.md" : requestedName;
        if (!name.endsWith(".md")) name += ".md";
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Path destination = root.resolve(name).normalize();
        if (!root.equals(destination.getParent()) || Files.isSymbolicLink(destination))
            throw new IOException("知识文件必须位于指定目录内");
        Files.createDirectories(root);
        Files.writeString(
                destination,
                "\n\n" + content,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
