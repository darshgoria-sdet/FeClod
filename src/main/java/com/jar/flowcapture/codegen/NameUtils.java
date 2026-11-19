package com.jar.flowcapture.codegen;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Converts a QA-typed free-text label ("tap abc button") into Java identifiers matching repo casing conventions. */
public class NameUtils {

    public static String toFieldName(String label) {
        String[] words = normalize(label);
        if (words.length == 0) return "element";
        StringBuilder sb = new StringBuilder(words[0].toLowerCase());
        for (int i = 1; i < words.length; i++) {
            sb.append(capitalize(words[i]));
        }
        return ensureValidJavaIdentifierStart(sb.toString());
    }

    public static String toMethodName(String label) {
        return toFieldName(label);
    }

    public static String toClassName(String label) {
        String[] words = normalize(label);
        if (words.length == 0) return "Flow";
        String joined = Arrays.stream(words).map(NameUtils::capitalize).collect(Collectors.joining());
        String withValidStart = ensureValidJavaIdentifierStart(joined);
        return Character.toUpperCase(withValidStart.charAt(0)) + withValidStart.substring(1);
    }

    /** A label like "123 button" normalizes to "123Button" — not a legal Java identifier. Prefix rather than drop the digits, so the generated name still reflects what QA typed. */
    private static String ensureValidJavaIdentifierStart(String identifier) {
        if (identifier.isEmpty() || Character.isDigit(identifier.charAt(0))) {
            return "el" + identifier;
        }
        return identifier;
    }

    /**
     * Splits camelCase/PascalCase humps before word-splitting, because capitalize() lowercases
     * everything after a word's first letter. Without this, a QA engineer typing the flow name
     * the way the dashboard's own placeholder suggests — "InstantSaveAbcButton" — gets
     * "Instantsaveabcbutton" back, in the class name, the file name and every generated
     * reference. Handles acronyms too, so "UPIFlow" splits to UPI + Flow rather than U + P + I.
     */
    private static String[] normalize(String label) {
        if (label == null) return new String[0];
        String spaced = label.trim()
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ");
        return Arrays.stream(spaced.split("\\s+"))
                .filter(w -> !w.isBlank())
                .toArray(String[]::new);
    }

    /**
     * Lowercases the tail so "BUTTON" and "button" both become "Button" — except for an
     * all-caps word of 2+ letters, which is treated as an acronym and left alone. Without
     * that exception "UPIFlow" comes back as "UpiFlow", renaming a domain term QA typed
     * deliberately.
     */
    private static String capitalize(String w) {
        if (w.isEmpty()) return w;
        if (w.length() > 1 && w.equals(w.toUpperCase()) && w.chars().anyMatch(Character::isLetter)) {
            return w;
        }
        return Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase();
    }
}
