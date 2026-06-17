package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProcessUtilsTest {
    @Test
    void runCommandReturnsOutputForSuccessfulCommand() {
        String result = ProcessUtils.runCommand(List.of("echo", "hello"));

        assertTrue(result.trim().contains("hello"));
    }

    @Test
    void runCommandThrowsForNonexistentCommand() {
        assertThrows(Exception.class, () -> ProcessUtils.runCommand(List.of("/nonexistent/command")));
    }

    @Test
    void runCommandReturnsOutputEvenForFailingCommandsWithErrorOutput() {
        String result = ProcessUtils.runCommand(List.of("ls", "/nonexistent/path/that/doesnt/exist"));

        assertTrue(!result.isBlank());
    }

    @Test
    void runCommandThrowsForEmptyArgumentList() {
        assertThrows(Exception.class, () -> ProcessUtils.runCommand(List.of()));
    }

    @Test
    void runCommandPreservesOutputForEcho() {
        String result = ProcessUtils.runCommand(List.of("echo", "world"));

        assertEquals("world", result.trim());
    }
}
