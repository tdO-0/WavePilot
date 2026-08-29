package org.example.wavepilot.knowledge.retrieval;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.example.wavepilot.knowledge.model.KnowledgeChunk;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Apache Lucene BM25 index. Chunk identity/provenance stays in the authoritative chunk catalog. */
@Component
public class LuceneBm25SparseRetriever implements SparseRetriever, AutoCloseable {
    private static final String CHUNK_ID = "chunkId";
    private static final String DOCUMENT_ID = "documentId";
    private static final String DOCUMENT_TYPE = "documentType";
    private static final String EXPERIMENT_TYPE = "experimentType";
    private static final String CONTENT = "content";

    private final Analyzer analyzer = new StandardAnalyzer();
    private final ByteBuffersDirectory directory = new ByteBuffersDirectory();
    private final IndexWriter writer;
    private final ConcurrentMap<String, KnowledgeChunk> catalog = new ConcurrentHashMap<>();

    public LuceneBm25SparseRetriever() {
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setSimilarity(new BM25Similarity());
            writer = new IndexWriter(directory, config);
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize Lucene BM25 index", e);
        }
    }

    @Override
    public synchronized void upsertDocument(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("Knowledge chunks are required");
        String documentId = chunks.get(0).metadata().documentId();
        if (chunks.stream().anyMatch(chunk -> !documentId.equals(chunk.metadata().documentId()))) {
            throw new IllegalArgumentException("All chunks in one upsert must share documentId");
        }
        try {
            writer.deleteDocuments(new Term(DOCUMENT_ID, documentId));
            catalog.entrySet().removeIf(entry -> documentId.equals(entry.getValue().metadata().documentId()));
            for (KnowledgeChunk chunk : chunks) {
                Document document = new Document();
                document.add(new StringField(CHUNK_ID, chunk.chunkId(), Field.Store.YES));
                document.add(new StringField(DOCUMENT_ID, documentId, Field.Store.NO));
                document.add(new StringField(DOCUMENT_TYPE, chunk.metadata().documentType().name(), Field.Store.NO));
                document.add(new StringField(EXPERIMENT_TYPE, chunk.metadata().experimentType().name(), Field.Store.NO));
                document.add(new TextField(CONTENT,
                        chunk.metadata().title() + " " + chunk.section() + " " + chunk.content(),
                        Field.Store.NO));
                writer.addDocument(document);
                catalog.put(chunk.chunkId(), chunk);
            }
            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Could not update Lucene BM25 index", e);
        }
    }

    @Override
    public synchronized List<RetrievalCandidate> search(KnowledgeSearchRequest request, int candidateK) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw new IllegalArgumentException("Knowledge query is required");
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            BooleanQuery.Builder query = new BooleanQuery.Builder();
            List<String> terms = analyze(request.query());
            for (String term : terms) {
                query.add(new TermQuery(new Term(CONTENT, term)), BooleanClause.Occur.SHOULD);
            }
            if (request.documentType() != null) {
                query.add(new TermQuery(new Term(DOCUMENT_TYPE, request.documentType().name())),
                        BooleanClause.Occur.FILTER);
            }
            if (request.experimentType() != null) {
                query.add(new TermQuery(new Term(EXPERIMENT_TYPE, request.experimentType().name())),
                        BooleanClause.Occur.FILTER);
            }
            if (terms.isEmpty()) return List.of();
            query.setMinimumNumberShouldMatch(1);
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            ScoreDoc[] hits = searcher.search(query.build(), Math.max(1, Math.min(100, candidateK))).scoreDocs;
            List<RetrievalCandidate> results = new ArrayList<>();
            for (ScoreDoc hit : hits) {
                String chunkId = searcher.storedFields().document(hit.doc).get(CHUNK_ID);
                KnowledgeChunk chunk = catalog.get(chunkId);
                if (chunk == null) continue;
                KnowledgeSearchResult evidence = KnowledgeSearchResult.from(chunk, hit.score)
                        .withScoreAndMethod(hit.score, "BM25");
                results.add(new RetrievalCandidate(evidence, hit.score));
            }
            return List.copyOf(results);
        } catch (IOException e) {
            throw new IllegalStateException("Lucene BM25 search failed", e);
        }
    }

    private List<String> analyze(String value) throws IOException {
        List<String> terms = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(CONTENT,
                new StringReader(value.toLowerCase(Locale.ROOT)))) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) terms.add(term.toString());
            stream.end();
        }
        return terms;
    }

    @Override
    public void close() throws Exception {
        writer.close();
        analyzer.close();
        directory.close();
    }
}
