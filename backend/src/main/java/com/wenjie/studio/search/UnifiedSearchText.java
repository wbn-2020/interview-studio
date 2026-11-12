package com.wenjie.studio.search;

import java.util.ArrayList;
import java.util.List;

final class UnifiedSearchText {
    private static final int MAX_FRAGMENTS = 3;
    private static final int FRAGMENT_LENGTH = 300;
    private static final int LEADING_CONTEXT = 60;

    private UnifiedSearchText() { }

    static List<UnifiedSearchDtos.Fragment> fragments(List<UnifiedSearchDtos.TextField> fields, String query) {
        var fragments = new ArrayList<UnifiedSearchDtos.Fragment>();
        for (UnifiedSearchDtos.TextField field : fields) {
            String text = field.text();
            int matchStart = firstMatch(text, query);
            if (matchStart < 0) continue;
            int matchEnd = matchStart + query.length();
            int start = Math.max(0, matchStart - LEADING_CONTEXT);
            if (splitsPair(text, start)) start--;
            int end = Math.min(text.length(), start + FRAGMENT_LENGTH);
            if (splitsPair(text, end)) end--;
            int lineStart = lineAt(text, matchStart);
            int lineEnd = lineAt(text, matchEnd - 1);
            fragments.add(new UnifiedSearchDtos.Fragment(field.id(), field.label(), text.substring(start, end),
                    start, end, matchStart, matchEnd, lineStart, lineEnd));
            if (fragments.size() == MAX_FRAGMENTS) break;
        }
        return List.copyOf(fragments);
    }

    private static int firstMatch(String text, String query) {
        // 转换大小写可能扩展字符；直接比较原文，确保偏移仍对应保存的确切字段。
        for (int index = 0; index <= text.length() - query.length(); index++) {
            if (!splitsPair(text, index) && !splitsPair(text, index + query.length())
                    && text.regionMatches(true, index, query, 0, query.length())) return index;
        }
        return -1;
    }

    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int index = 0; index < offset; index++) {
            char character = text.charAt(index);
            // 与历史原文页按 CRLF／CR／LF 分行一致；CRLF 在 LF 之后才进入下一行。
            if (character == '\n' || character == '\r'
                    && (index + 1 == text.length() || text.charAt(index + 1) != '\n')) line++;
        }
        return line;
    }

    private static boolean splitsPair(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }
}
