package dev.wacamera.recorder;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExecutableResolverTest {
    @Test void aBareExecutableNameUsesPath() throws Exception {
        var java = ExecutableResolver.findOnPath("java");
        assumeTrue(java.isPresent());
        assertEquals(java.get(), ExecutableResolver.resolveFfmpeg(Path.of("java")));
    }

    @Test void anExplicitMissingDirectoryDoesNotFallBackToPath() {
        assertThrows(java.io.IOException.class,
                () -> ExecutableResolver.resolveFfmpeg(Path.of("missing-wacamera-test-directory", "java")));
    }
}
