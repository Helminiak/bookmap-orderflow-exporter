package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.lang.reflect.Method;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class BridgeJsonEscapingTest {
    private static final String[] CONTROL_ESCAPES = {
        "\\u0000", "\\u0001", "\\u0002", "\\u0003", "\\u0004", "\\u0005", "\\u0006", "\\u0007",
        "\\u0008", "\\u0009", "\\n", "\\u000b", "\\u000c", "\\r", "\\u000e", "\\u000f",
        "\\u0010", "\\u0011", "\\u0012", "\\u0013", "\\u0014", "\\u0015", "\\u0016", "\\u0017",
        "\\u0018", "\\u0019", "\\u001a", "\\u001b", "\\u001c", "\\u001d", "\\u001e", "\\u001f"
    };

    private static String quote(String value) throws Exception {
        Method method = LiveBridge.class.getDeclaredMethod("quote", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static void assertQuoted(String input, String expected) throws Exception {
        String actual = quote(input);
        assertAll(
                () -> assertEquals(expected, actual),
                () ->
                        assertTrue(
                                actual.chars().noneMatch(c -> c < 0x20),
                                "JSON string must not contain literal control characters"));
    }

    @TestFactory
    Stream<DynamicTest> escapesEveryJsonControlCharacter() {
        return IntStream.range(0, 32)
                .mapToObj(
                        control ->
                                DynamicTest.dynamicTest(
                                        "control U+" + String.format("%04X", control),
                                        () ->
                                                assertQuoted(
                                                        String.valueOf((char) control),
                                                        "\"" + CONTROL_ESCAPES[control] + "\"")));
    }

    @Test
    void escapesAdjacentControlsInsideMixedText() throws Exception {
        StringBuilder controls = new StringBuilder();
        for (int control = 0; control < 32; control++) controls.append((char) control);
        assertQuoted(
                "before/\"\\" + controls + "é😀after",
                "\"before/\\\"\\\\" + String.join("", CONTROL_ESCAPES) + "é😀after\"");
    }

    @Test
    void preservesOrdinaryUnicodeQuotesBackslashesAndSlash() throws Exception {
        assertQuoted(
                "plain text / café 漢字 😀 \"quoted\" \\ literal \\n \\u0000",
                "\"plain text / café 漢字 😀 \\\"quoted\\\" \\\\ literal \\\\n \\\\u0000\"");
    }

    @Test
    void quotesEmptyString() throws Exception {
        assertQuoted("", "\"\"");
    }
}
