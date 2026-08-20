package org.example.wavepilot.knowledge;

import org.example.wavepilot.config.DocumentChunkProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Splits uploaded documents without depending on the removed compatibility application. */
@Service
public class DocumentChunkService {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    private final DocumentChunkProperties properties;

    public DocumentChunkService(DocumentChunkProperties properties) {
        this.properties = properties;
    }

    /** Convenience constructor for deterministic unit-test fixtures. */
    public DocumentChunkService() {
        this(new DocumentChunkProperties());
    }

    public List<DocumentChunk> chunkDocument(String content, String source) {
        if (content == null || content.isBlank()) return List.of();
        List<Section> sections = splitByHeadings(content);
        List<DocumentChunk> chunks = new ArrayList<>();
        int nextIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, nextIndex);
            chunks.addAll(sectionChunks);
            nextIndex += sectionChunks.size();
        }
        return chunks;
    }

    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Matcher matcher = HEADING.matcher(content);
        int sectionStart = 0;
        String title = null;
        while (matcher.find()) {
            if (sectionStart < matcher.start()) {
                addSection(sections, title, content.substring(sectionStart, matcher.start()), sectionStart);
            }
            title = matcher.group(2).trim();
            sectionStart = matcher.end();
        }
        if (sectionStart < content.length()) {
            addSection(sections, title, content.substring(sectionStart), sectionStart);
        }
        if (sections.isEmpty()) sections.add(new Section(null, content.trim(), 0));
        return sections;
    }

    private void addSection(List<Section> sections, String title, String text, int start) {
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) sections.add(new Section(title, trimmed, start));
    }

    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        if (section.content().length() <= properties.getMaxSize()) {
            DocumentChunk chunk = new DocumentChunk(section.content(), section.startIndex(),
                    section.startIndex() + section.content().length(), startChunkIndex);
            chunk.setTitle(section.title());
            return List.of(chunk);
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentStart = section.startIndex();
        int chunkIndex = startChunkIndex;
        for (String paragraph : section.content().split("\\R\\s*\\R+")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;
            if (current.length() > 0 && current.length() + trimmed.length() + 2 > properties.getMaxSize()) {
                String chunkText = current.toString().trim();
                chunks.add(chunk(chunkText, section.title(), currentStart, chunkIndex++));
                String overlap = overlap(chunkText);
                current = new StringBuilder(overlap);
                currentStart += chunkText.length() - overlap.length();
            }
            current.append(trimmed).append("\n\n");
        }
        if (current.length() > 0) {
            chunks.add(chunk(current.toString().trim(), section.title(), currentStart, chunkIndex));
        }
        return chunks;
    }

    private DocumentChunk chunk(String text, String title, int start, int index) {
        DocumentChunk chunk = new DocumentChunk(text, start, start + text.length(), index);
        chunk.setTitle(title);
        return chunk;
    }

    private String overlap(String text) {
        int size = Math.min(properties.getOverlap(), text.length());
        if (size <= 0) return "";
        String tail = text.substring(text.length() - size);
        int sentence = Math.max(tail.lastIndexOf('。'), Math.max(tail.lastIndexOf('？'), tail.lastIndexOf('！')));
        return sentence > size / 2 ? tail.substring(sentence + 1).trim() : tail.trim();
    }

    private record Section(String title, String content, int startIndex) { }
}
