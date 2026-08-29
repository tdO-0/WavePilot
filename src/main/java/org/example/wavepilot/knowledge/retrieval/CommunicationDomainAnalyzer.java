package org.example.wavepilot.knowledge.retrieval;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKBigramFilter;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.pattern.PatternReplaceCharFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import java.io.Reader;
import java.util.regex.Pattern;

/**
 * Lightweight Lucene analyzer for bilingual communication-engineering material.
 *
 * <p>It keeps acronyms such as BPSK/AWGN/BER intact, normalizes Eb/N0 to the
 * searchable token {@code ebn0}, emits CJK bigrams, and emits both the original
 * and component forms of camelCase/snake_case identifiers. No external search
 * cluster or language service is involved.</p>
 */
public final class CommunicationDomainAnalyzer extends Analyzer {
    private static final Pattern EBN0 = Pattern.compile("(?i)\\bEb\\s*/\\s*N0\\b");
    private static final Pattern CAMEL_CASE = Pattern.compile(
            "\\b([A-Za-z]*[a-z])([A-Z][A-Za-z0-9]*)\\b");
    private static final int WORD_FLAGS = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
            | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
            | WordDelimiterGraphFilter.CATENATE_WORDS
            | WordDelimiterGraphFilter.CATENATE_NUMBERS
            | WordDelimiterGraphFilter.CATENATE_ALL
            | WordDelimiterGraphFilter.PRESERVE_ORIGINAL
            | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE;

    @Override
    protected Reader initReader(String fieldName, Reader reader) {
        Reader normalized = new PatternReplaceCharFilter(EBN0, "EbN0", reader);
        return new PatternReplaceCharFilter(CAMEL_CASE, "$1$2 $1 $2", normalized);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        StandardTokenizer tokenizer = new StandardTokenizer();
        TokenStream stream = new WordDelimiterGraphFilter(tokenizer, WORD_FLAGS, CharArraySet.EMPTY_SET);
        stream = new CJKBigramFilter(stream);
        stream = new LowerCaseFilter(stream);
        stream = new ASCIIFoldingFilter(stream, true);
        return new TokenStreamComponents(tokenizer, stream);
    }
}
