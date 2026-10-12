package dev.wacamera.recorder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ProcessTools {
    private ProcessTools() {}

    static void run(List<String> command, Path output, Path error, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        process.getOutputStream().close();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Process timed out: " + command.get(0) + "; see " + error);
            }
            if (process.exitValue() != 0) throw new IOException("Process exited with code " + process.exitValue() + "; see " + error);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Process wait interrupted", e);
        }
    }
}
