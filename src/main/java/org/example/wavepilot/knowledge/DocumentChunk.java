package org.example.wavepilot.knowledge;

/** One semantic fragment produced from an uploaded communication-domain document. */
public final class DocumentChunk {

    private final String content;
    private final int startIndex;
    private final int endIndex;
    private final int chunkIndex;
    private String title;

    public DocumentChunk(String content, int startIndex, int endIndex, int chunkIndex) {
        this.content = content;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkIndex = chunkIndex;
    }

    public String getContent() { return content; }
    public int getStartIndex() { return startIndex; }
    public int getEndIndex() { return endIndex; }
    public int getChunkIndex() { return chunkIndex; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
}
