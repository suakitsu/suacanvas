package com.openimage.storage;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class LibvipsHeifImageConverter implements HeifImageConverter {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);

    @Override
    public ConversionResult convert(byte[] source, long maxPixels) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("picpilot-heif-");
            Path input = directory.resolve("source.heic");
            Path output = directory.resolve("normalized.jpg");
            Files.write(input, source);

            int width = probe(input, "width");
            int height = probe(input, "height");
            if (width <= 0 || height <= 0) {
                throw corrupt(null);
            }
            if ((long) width * height > maxPixels) {
                throw new ImageUploadException("IMAGE_PIXEL_LIMIT", "图片像素过大，请缩小图片尺寸后再上传");
            }

            run(List.of(
                "vips",
                "autorot",
                input.toString(),
                output + "[Q=92,strip,optimize_coding]"
            ));
            if (!Files.isRegularFile(output)) {
                throw corrupt(null);
            }
            byte[] jpeg = Files.readAllBytes(output);
            if (jpeg.length < 3 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8 || (jpeg[2] & 0xff) != 0xff) {
                throw corrupt(null);
            }
            return new ConversionResult(jpeg, width, height);
        } catch (ImageUploadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw corrupt(exception);
        } finally {
            deleteDirectory(directory);
        }
    }

    private int probe(Path input, String field) throws IOException, InterruptedException {
        ProcessResult result = run(List.of("vipsheader", "-f", field, input.toString()));
        try {
            return Integer.parseInt(result.output().trim());
        } catch (NumberFormatException exception) {
            throw corrupt(exception);
        }
    }

    private ProcessResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        boolean completed = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw corrupt(null);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw corrupt(null);
        }
        return new ProcessResult(output);
    }

    private ImageUploadException corrupt(Throwable cause) {
        return new ImageUploadException("IMAGE_CORRUPT", "HEIC/HEIF 照片已损坏或无法解析，请重新选择图片", cause);
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary files are best-effort cleanup only.
                }
            });
        } catch (IOException ignored) {
            // Do not expose temporary paths to callers.
        }
    }

    private record ProcessResult(String output) {}
}
