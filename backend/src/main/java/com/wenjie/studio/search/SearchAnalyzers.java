package com.wenjie.studio.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.miscellaneous.LengthFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.ngram.NGramTokenizer;
import org.apache.lucene.analysis.pattern.PatternTokenizer;

import java.util.Map;
import java.util.regex.Pattern;

final class SearchAnalyzers {
    private static final Pattern CODE_SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}_$.@]+");
    private SearchAnalyzers() { }

    static Analyzer create() {
        return new PerFieldAnalyzerWrapper(new CJKAnalyzer(), Map.of(
                "code_exact", code(false), "code_parts", code(true), "character", characters()));
    }

    private static Analyzer code(boolean split) {
        return new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String fieldName) {
                PatternTokenizer tokenizer = new PatternTokenizer(CODE_SEPARATOR, -1);
                TokenStream stream = new LengthFilter(tokenizer, 1, 1024);
                if (split) {
                    int flags = WordDelimiterGraphFilter.GENERATE_WORD_PARTS
                            | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
                            | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
                            | WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
                            | WordDelimiterGraphFilter.PRESERVE_ORIGINAL;
                    stream = new WordDelimiterGraphFilter(stream, flags, null);
                }
                return new TokenStreamComponents(tokenizer, new LowerCaseFilter(stream));
            }
        };
    }

    private static Analyzer characters() {
        return new Analyzer() {
            @Override protected TokenStreamComponents createComponents(String fieldName) {
                NGramTokenizer tokenizer = new NGramTokenizer(1, 1) {
                    @Override protected boolean isTokenChar(int codePoint) { return Character.isLetterOrDigit(codePoint); }
                };
                return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
            }
        };
    }
}
