package com.jar.flowcapture.codegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NameUtilsTest {

    @Test
    void producesCamelCaseFieldName() {
        assertEquals("tapAbcButton", NameUtils.toFieldName("tap abc button"));
    }

    @Test
    void producesPascalCaseClassName() {
        assertEquals("TapAbcButton", NameUtils.toClassName("tap abc button"));
    }

    @Test
    void digitLeadingLabelProducesValidJavaIdentifier() {
        String field = NameUtils.toFieldName("123 button");
        assertFalse(Character.isDigit(field.charAt(0)), "field name must not start with a digit: " + field);

        String className = NameUtils.toClassName("123 button");
        assertFalse(Character.isDigit(className.charAt(0)), "class name must not start with a digit: " + className);
        assertTrue(Character.isUpperCase(className.charAt(0)), "class name must start uppercase: " + className);
    }

    @Test
    void degenerateAllSymbolLabelFallsBackInsteadOfEmptyString() {
        assertFalse(NameUtils.toClassName("???").isEmpty());
        assertFalse(NameUtils.toFieldName("???").isEmpty());
    }

    @Test
    void nullLabelDoesNotThrow() {
        assertEquals("element", NameUtils.toFieldName(null));
        assertFalse(NameUtils.toClassName(null).isEmpty());
    }

    /**
     * The dashboard's own placeholder tells QA to type "InstantSaveAbcButton". Before
     * camelCase splitting that produced "Instantsaveabcbutton" in the class name, the file
     * name and every generated reference to it.
     */
    @Test
    void camelCaseFlowNameKeepsItsWordBoundaries() {
        assertEquals("InstantSaveAbcButton", NameUtils.toClassName("InstantSaveAbcButton"));
        assertEquals("instantSaveAbcButton", NameUtils.toFieldName("InstantSaveAbcButton"));
    }

    /** An acronym must not be exploded into one word per letter. */
    @Test
    void acronymsStayIntact() {
        assertEquals("UPIFlow", NameUtils.toClassName("UPIFlow"));
    }

    /** Space-separated labels must keep working exactly as before. */
    @Test
    void spaceSeparatedLabelsAreUnaffected() {
        assertEquals("TapAbcButton", NameUtils.toClassName("tap abc button"));
        assertEquals("tapAbcButton", NameUtils.toFieldName("tap abc button"));
    }
}
