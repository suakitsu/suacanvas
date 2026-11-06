package com.openimage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageUploadNormalizerTest {
    private static final byte[] WEBP_1X1 = Base64.getDecoder().decode(
        "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADMDOJaQAA3AA/vuUAAA="
    );

    @Test
    void acceptsJpegPngAndWebpByActualBytes() throws Exception {
        ImageUploadNormalizer normalizer = normalizerReturning(jpeg(2, 3));

        ImageUploadNormalizer.NormalizedImage jpeg = normalizer.normalize(file("photo.jpg", "image/jpeg", jpeg(4, 3)));
        ImageUploadNormalizer.NormalizedImage png = normalizer.normalize(file("photo.png", "image/png", png(5, 2)));
        ImageUploadNormalizer.NormalizedImage webp = normalizer.normalize(file("photo.webp", "image/webp", WEBP_1X1));

        assertThat(jpeg.format()).isEqualTo("jpeg");
        assertThat(jpeg.width()).isEqualTo(4);
        assertThat(jpeg.height()).isEqualTo(3);
        assertThat(png.format()).isEqualTo("png");
        assertThat(png.width()).isEqualTo(5);
        assertThat(png.height()).isEqualTo(2);
        assertThat(webp.format()).isEqualTo("webp");
        assertThat(webp.width()).isEqualTo(1);
        assertThat(webp.height()).isEqualTo(1);
    }

    @Test
    void convertsImageHeicAndImageHeifToRealJpeg() throws Exception {
        ImageUploadNormalizer normalizer = normalizerReturning(jpeg(3, 5));

        for (String contentType : new String[] {"image/heic", "image/heif"}) {
            ImageUploadNormalizer.NormalizedImage image = normalizer.normalize(file("iphone.heic", contentType, heicHeader()));

            assertThat(image.converted()).isTrue();
            assertThat(image.filename()).isEqualTo("iphone.jpg");
            assertThat(image.contentType()).isEqualTo("image/jpeg");
            assertThat(image.format()).isEqualTo("jpeg");
            assertThat(image.extension()).isEqualTo(".jpg");
            assertThat(image.width()).isEqualTo(3);
            assertThat(image.height()).isEqualTo(5);
            assertThat(image.fileSize()).isEqualTo(image.bytes().length);
            assertThat(image.bytes()).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff);
        }
    }

    @Test
    void detectsHeicWithEmptyOrOctetStreamMimeAndUppercaseExtension() throws Exception {
        ImageUploadNormalizer normalizer = normalizerReturning(jpeg(2, 4));

        ImageUploadNormalizer.NormalizedImage emptyMime = normalizer.normalize(file("IMG_1001.HEIC", "", heicHeader()));
        ImageUploadNormalizer.NormalizedImage octetStream = normalizer.normalize(file("IMG_1002", "application/octet-stream", heicHeader()));

        assertThat(emptyMime.filename()).isEqualTo("IMG_1001.jpg");
        assertThat(emptyMime.contentType()).isEqualTo("image/jpeg");
        assertThat(octetStream.filename()).isEqualTo("IMG_1002.jpg");
        assertThat(octetStream.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void rejectsFakeJpgAndCorruptHeicWithExplicitCodes() throws Exception {
        ImageUploadNormalizer normalizer = normalizerReturning(jpeg(2, 3));
        ImageUploadNormalizer brokenConverter = new ImageUploadNormalizer((source, maxPixels) -> {
            throw new ImageUploadException("IMAGE_CORRUPT", "HEIC/HEIF 照片已损坏或无法解析，请重新选择图片");
        });

        assertThatThrownBy(() -> normalizer.normalize(file("fake.jpg", "image/jpeg", "not-an-image".getBytes())))
            .isInstanceOfSatisfying(ImageUploadException.class, error -> assertThat(error.code()).isEqualTo("IMAGE_CORRUPT"));
        assertThatThrownBy(() -> brokenConverter.normalize(file("broken.heic", "image/heic", heicHeader())))
            .isInstanceOfSatisfying(ImageUploadException.class, error -> assertThat(error.code()).isEqualTo("IMAGE_CORRUPT"))
            .hasMessageContaining("无法解析");
    }

    @Test
    void convertedDimensionsReflectAutorotatedJpeg() throws Exception {
        ImageUploadNormalizer normalizer = normalizerReturning(jpeg(3, 7));

        ImageUploadNormalizer.NormalizedImage image = normalizer.normalize(file("portrait.heic", "image/heic", heicHeader()));

        assertThat(image.width()).isEqualTo(3);
        assertThat(image.height()).isEqualTo(7);
    }

    @Test
    void geminiNormalizationConvertsWebpToRealJpeg() throws Exception {
        byte[] convertedJpeg = jpeg(8, 5);
        ImageUploadNormalizer normalizer = new ImageUploadNormalizer(
            (source, maxPixels) -> new HeifImageConverter.ConversionResult(convertedJpeg, 8, 5),
            (source, sourceFormat, maxPixels) -> new RasterImageConverter.ConversionResult(convertedJpeg)
        );

        ImageUploadNormalizer.NormalizedImage image = normalizer.normalizeForJpegOrPng(
            file("product.webp", "image/webp", WEBP_1X1)
        );

        assertThat(image.filename()).isEqualTo("product.jpg");
        assertThat(image.contentType()).isEqualTo("image/jpeg");
        assertThat(image.format()).isEqualTo("jpeg");
        assertThat(image.width()).isEqualTo(8);
        assertThat(image.height()).isEqualTo(5);
        assertThat(image.converted()).isTrue();
        assertThat(image.bytes()).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff);
    }

    @Test
    void localStorageWritesNormalizedBytesAndJpegUrlForUpstream(@TempDir Path directory) throws Exception {
        ImageUploadNormalizer normalizer = normalizerReturning(jpeg(6, 4));
        StorageProperties properties = new StorageProperties(
            "local", directory.toString(), "", "", "", "", "", "", true, false
        );
        LocalStorageService storage = new LocalStorageService(properties, normalizer);

        LocalStorageService.StoredFile stored = storage.saveProjectImage(
            "user-1", "video-task-1", "source-1", file("iphone.heic", "application/octet-stream", heicHeader())
        );

        assertThat(stored.url()).endsWith("/source-1.jpg");
        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.format()).isEqualTo("jpeg");
        assertThat(stored.width()).isEqualTo(6);
        assertThat(stored.height()).isEqualTo(4);
        assertThat(Files.readAllBytes(Path.of(stored.path()))).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff);
    }

    private ImageUploadNormalizer normalizerReturning(byte[] jpeg) {
        return new ImageUploadNormalizer((source, maxPixels) -> new HeifImageConverter.ConversionResult(jpeg, 7, 3));
    }

    private MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    private byte[] heicHeader() {
        byte[] bytes = new byte[32];
        bytes[3] = 32;
        System.arraycopy("ftyp".getBytes(), 0, bytes, 4, 4);
        System.arraycopy("heic".getBytes(), 0, bytes, 8, 4);
        System.arraycopy("mif1".getBytes(), 0, bytes, 16, 4);
        return bytes;
    }

    private byte[] jpeg(int width, int height) throws Exception {
        return image(width, height, "jpg");
    }

    private byte[] png(int width, int height) throws Exception {
        return image(width, height, "png");
    }

    private byte[] image(int width, int height, String format) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(31, 92, 210));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output)).isTrue();
        return output.toByteArray();
    }
}
