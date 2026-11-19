package com.jar.flowcapture.codegen;

/**
 * Escapes a string for safe embedding inside a double-quoted Java string literal in
 * generated source. This is distinct from, and applied AFTER, any XPath/UiSelector-level
 * escaping (e.g. LocatorRanker swapping embedded double-quotes for single-quotes so the
 * XPath expression itself stays syntactically valid) — that earlier step produces a
 * correct *locator expression*; this step makes that expression safe to write as Java
 * source text.
 *
 * Missing this step is what caused a two-line button label (e.g. "Festival\nSavings",
 * common for wrapped on-screen text) to be written as a literal line break inside a
 * Java string literal in a generated file, breaking compilation.
 */
public class JavaLiteralEscaper {

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
