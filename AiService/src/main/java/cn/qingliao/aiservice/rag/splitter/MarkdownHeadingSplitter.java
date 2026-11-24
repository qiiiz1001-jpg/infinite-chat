package cn.qingliao.aiservice.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

public class MarkdownHeadingSplitter implements DocumentSplitter {

    private final String headingPrefix; // headingLevel=3 -> "###"
    private final int maxCharsPerSegment; // <=0 表示不做兜底细分
    private final DocumentSplitter subSplitter;

    public MarkdownHeadingSplitter(
            int headingLevel, int maxCharsPerSegment, DocumentSplitter subSplitter) {
        if (headingLevel < 1) {
            throw new IllegalArgumentException("headingLevel 必须 >= 1");
        }
        this.headingPrefix = "#".repeat(headingLevel);
        this.maxCharsPerSegment = maxCharsPerSegment;
        this.subSplitter = subSplitter;
    }

    /** 按指定级别标题切块，过长块交给备用切分器。 */
    @Override
    public List<TextSegment> split(Document document) {
        List<TextSegment> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : document.text().split("\n", -1)) {
            if (isHeading(line) && current.length() > 0) {
                flush(current, document, segments);
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        flush(current, document, segments);
        return segments;
    }

    // 仅判断前缀，不解析 Markdown 代码围栏或完整标题语法。
    private boolean isHeading(String line) {
        String t = line.stripLeading();
        return t.startsWith(headingPrefix) && !t.startsWith(headingPrefix + "#");
    }

    private void flush(StringBuilder buf, Document document, List<TextSegment> out) {
        String content = buf.toString().strip();
        if (content.isEmpty()) {
            return;
        }
        if (maxCharsPerSegment > 0
                && content.length() > maxCharsPerSegment
                && subSplitter != null) {
            out.addAll(subSplitter.split(Document.from(content, document.metadata())));
        } else {
            out.add(TextSegment.from(content, document.metadata()));
        }
    }
}
