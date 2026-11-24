package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.*;

import cn.qingliao.aiservice.knowledge.KnowledgeFiles;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;

class KnowledgeFilesTest {
    @TempDir Path directory;

    @Test
    void missingNameUsesDefaultAndAppendsUtf8() throws Exception {
        KnowledgeFiles.append(directory.toString(), null, "第一条知识");
        KnowledgeFiles.append(directory.toString(), "Qingliao.md", "第二条知识");
        String text = Files.readString(directory.resolve("Qingliao.md"));
        assertTrue(text.contains("第一条知识"));
        assertTrue(text.contains("第二条知识"));
    }

    @Test
    void toolFileNameGetsMarkdownSuffix() throws Exception {
        KnowledgeFiles.append(directory.toString(), "notes", "内容");
        assertTrue(Files.exists(directory.resolve("notes.md")));
    }

    @Test
    void traversalAndAbsolutePathsCannotEscapeKnowledgeDirectory() {
        assertThrows(
                IOException.class,
                () -> KnowledgeFiles.append(directory.toString(), "../outside.md", "bad"));
        assertThrows(
                IOException.class,
                () ->
                        KnowledgeFiles.append(
                                directory.toString(),
                                directory.getParent().resolve("outside.md").toString(),
                                "bad"));
        assertFalse(Files.exists(directory.getParent().resolve("outside.md")));
    }
}
