package io.zodia.pgledger.store;

import java.util.ArrayList;
import java.util.List;

/** Splits a SQL script on semicolons that are outside dollar quotes. */
public final class SqlScripts {
    private SqlScripts() {
    }

    public static List<String> statements(String sql) {
        ArrayList<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder(sql.length());
        String openTag = null;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '$') {
                int tagEnd = tagEnd(sql, i);
                if (tagEnd > i) {
                    String tag = sql.substring(i, tagEnd + 1);
                    if (openTag == null) {
                        openTag = tag;
                        current.append(tag);
                        i = tagEnd;
                        continue;
                    }
                    if (tag.equals(openTag)) {
                        openTag = null;
                        current.append(tag);
                        i = tagEnd;
                        continue;
                    }
                }
            }
            if (openTag == null && c == ';') {
                add(statements, current);
                continue;
            }
            current.append(c);
        }
        add(statements, current);
        return statements;
    }

    private static int tagEnd(String sql, int start) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '$') {
                return i;
            }
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return -1;
            }
            i++;
        }
        return -1;
    }

    private static void add(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        current.setLength(0);
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }
}
