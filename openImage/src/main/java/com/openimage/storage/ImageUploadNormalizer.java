package com.openimage.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Service
public class ImageUploadNormalizer {
    public static final long DEFAULT_MAX_BYTES = 25L * 1024L * 1024L;
    public static final long DEFAULT_MAX_PIXELS = 50_000_000L;

    private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "mif1", "msf1");
    private final HeifImageConverter heifConverter;
    private final RasterImageConverter rasterConverter;

    public ImageUploadNormalizer(HeifImageConverter heifConverter) {
        this(heifConverter, (source, sourceFormat, maxPixels) -> {
            throw new ImageUploadException(
                "IMAGE_CONVERSION_FAILED",
                "该图片无法转换为标准 JPEG，请重新选择 JPG、PNG、WebP 或 HEIC 图片"
            );
        });
    }

    @Autowired
    public ImageUploadNormalizer(
        HeifImageConverter heifConverter,
        RasterImageConverter rasterConverter
    ) {
        this.heifConverter = heifConverter;
        this.rasterConverter = rasterConverter;
    }

    public NormalizedImage normalize(MultipartFile file) throws IOException {
        return normalize(file, DEFAULT_MAX_BYTES, DEFAULT_MAX_PIXELS);
    }

    public NormalizedImage normalize(MultipartFile file, long maxBytes, long maxPixels) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ImageUploadException("IMAGE_CORRUPT", "图片文件为空或已损坏，请重新选择图片");
        }
        return normalize(
            file.getBytes(),
            file.getOriginalFilename(),
            file.getContentType(),
            maxBytes,
            maxPixels
        );
    }

    public NormalizedImage normalize(
        byte[] source,
        String originalFilename,
        String contentType,
        long maxBytes,
        long maxPixels
    ) throws IOException {
        if (source == null || source.length == 0) {
            throw new ImageUploadException("IMAGE_CORRUPT", "图片文件为空或已损坏，请重新选择图片");
        }
        if (source.length > maxBytes) {
            throw new ImageUploadException("UPLOAD_TOO_LARGE", "图片文件过大，请选择更小的图片");
        }

        ImageFormat detected = detect(source);
        if (detected == null) {
            throw invalidType(originalFilename, contentType);
        }

        byte[] normalizedBytes = source;
        boolean converted = false;
        if (detected == ImageFormat.HEIC) {
            normalizedBytes = heifConverter.convert(source, maxPixels).jpegBytes();
            detected = ImageFormat.JPEG;
            converted = true;
        }

        Dimensions dimensions = dimensions(normalizedBytes, detected);
        if (dimensions.width() <= 0 || dimensions.height() <= 0) {
            throw corrupt();
        }
        if ((long) dimensions.width() * dimensions.height() > maxPixels) {
            throw new ImageUploadException("IMAGE_PIXEL_LIMIT", "图片像素过大，请缩小图片尺寸后再上传");
        }

        return new NormalizedImage(
            normalizedBytes,
            normalizedFilename(originalFilename, detected.extension()),
            detected.contentType(),
            detected.format(),
            detected.extension(),
            dimensions.width(),
            dimensions.height(),
            converted
        );
    }

    public NormalizedImage normalizeForJpegOrPng(MultipartFile file) throws IOException {
        return normalizeForJpegOrPng(file, DEFAULT_MAX_BYTES, DEFAULT_MAX_PIXELS);
    }

    public NormalizedImage normalizeForJpegOrPng(
        MultipartFile file,
        long maxBytes,
        long maxPixels
    ) throws IOException {
        NormalizedImage image = normalize(file, maxBytes, maxPixels);
        if (!"webp".equals(image.format())) return image;

        byte[] jpeg = rasterConverter.convertToJpeg(image.bytes(), image.format(), maxPixels).jpegBytes();
        Dimensions dimensions = dimensions(jpeg, ImageFormat.JPEG);
        if ((long) dimensions.width() * dimensions.height() > maxPixels) {
            throw new ImageUploadException("IMAGE_PIXEL_LIMIT", "图片像素过大，请缩小图片尺寸后再上传");
        }
        return new NormalizedImage(
            jpeg,
            normalizedFilename(image.filename(), ImageFormat.JPEG.extension()),
            ImageFormat.JPEG.contentType(),
            ImageFormat.JPEG.format(),
            ImageFormat.JPEG.extension(),
            dimensions.width(),
            dimensions.height(),
            true
        );
    }

    private ImageFormat detect(byte[] bytes) {
        if (bytes.length >= 3 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8 && unsigned(bytes[2]) == 0xff) {
            return ImageFormat.JPEG;
        }
        if (bytes.length >= 8
            && unsigned(bytes[0]) == 0x89
            && "PNG".equals(ascii(bytes, 1, 3))
            && unsigned(bytes[4]) == 0x0d
            && unsigned(bytes[5]) == 0x0a
            && unsigned(bytes[6]) == 0x1a
            && unsigned(bytes[7]) == 0x0a) {
            return ImageFormat.PNG;
        }
        if (bytes.length >= 12 && "RIFF".equals(ascii(bytes, 0, 4)) && "WEBP".equals(ascii(bytes, 8, 4))) {
            return ImageFormat.WEBP;
        }
        if (isHeif(bytes)) {
            return ImageFormat.HEIC;
        }
        return null;
    }

    private boolean isHeif(byte[] bytes) {
        if (bytes.length < 12 || !"ftyp".equals(ascii(bytes, 4, 4))) return false;
        int limit = Math.min(bytes.length, 256);
        for (int offset = 8; offset + 4 <= limit; offset += 4) {
            if (HEIF_BRANDS.contains(ascii(bytes, offset, 4).toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private Dimensions dimensions(byte[] bytes, ImageFormat format) throws IOException {
        if (format == ImageFormat.WEBP) {
            Dimensions dimensions = webpDimensions(bytes);
            if (dimensions == null) throw corrupt();
            return dimensions;
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream == null) throw corrupt();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw corrupt();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height <= DEFAULT_MAX_PIXELS) {
                    BufferedImage decoded = reader.read(0);
                    if (decoded == null) throw corrupt();
                }
                return new Dimensions(width, height);
            } catch (ImageUploadException exception) {
                throw exception;
            } catch (Exception exception) {
                throw corrupt();
            } finally {
                reader.dispose();
            }
        }
    }

    private Dimensions webpDimensions(byte[] bytes) {
        if (bytes.length < 30) return null;
        String chunk = ascii(bytes, 12, 4);
        if ("VP8X".equals(chunk)) {
            return new Dimensions(1 + littleEndian24(bytes, 24), 1 + littleEndian24(bytes, 27));
        }
        if ("VP8 ".equals(chunk) && unsigned(bytes[23]) == 0x9d && unsigned(bytes[24]) == 0x01 && unsigned(bytes[25]) == 0x2a) {
            return new Dimensions(littleEndian16(bytes, 26) & 0x3fff, littleEndian16(bytes, 28) & 0x3fff);
        }
        if ("VP8L".equals(chunk) && unsigned(bytes[20]) == 0x2f) {
            int b0 = unsigned(bytes[21]);
            int b1 = unsigned(bytes[22]);
            int b2 = unsigned(bytes[23]);
            int b3 = unsigned(bytes[24]);
            return new Dimensions(1 + b0 + ((b1 & 0x3f) << 8), 1 + ((b1 & 0xc0) >> 6) + (b2 << 2) + ((b3 & 0x0f) << 10));
        }
        return null;
    }

    private ImageUploadException invalidType(String originalFilename, String contentType) {
        String name = lower(originalFilename);
        String type = lower(contentType);
        boolean supportedHint = name.matches(".*\\.(jpe?g|png|webp|heic|heif)$")
            || Set.of("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif").contains(type);
        if (supportedHint) return corrupt();
        return new ImageUploadException("IMAGE_FORMAT_UNSUPPORTED", "该照片格式暂时无法解析，请选择 JPG、PNG、WebP 或 HEIC 图片");
    }

    private ImageUploadException corrupt() {
        return new ImageUploadException("IMAGE_CORRUPT", "图片文件已损坏或无法读取，请重新选择图片");
    }

    private String normalizedFilename(String originalFilename, String extension) {
        String original = originalFilename == null ? "" : originalFilename.replace("\\", "/");
        int slash = original.lastIndexOf('/');
        String name = slash >= 0 ? original.substring(slash + 1) : original;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "-").trim();
        if (base.isBlank()) base = "image";
        if (base.length() > 180) base = base.substring(0, 180);
        return base + extension;
    }

    private int littleEndian16(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8);
    }

    private int littleEndian24(byte[] bytes, int offset) {
        return unsigned(bytes[offset]) | (unsigned(bytes[offset + 1]) << 8) | (unsigned(bytes[offset + 2]) << 16);
    }

    private int unsigned(byte value) {
        return value & 0xff;
    }

    private String ascii(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset + length > bytes.length) return "";
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private enum ImageFormat {
        JPEG("jpeg", "image/jpeg", ".jpg"),
        PNG("png", "image/png", ".png"),
        WEBP("webp", "image/webp", ".webp"),
        HEIC("heic", "image/heic", ".heic");

        private final String format;
        private final String contentType;
        private final String extension;

        ImageFormat(String format, String contentType, String extension) {
            this.format = format;
            this.contentType = contentType;
            this.extension = extension;
        }

        String format() { return format; }
        String contentType() { return contentType; }
        String extension() { return extension; }
    }

    private record Dimensions(int width, int height) {}

    public record NormalizedImage(
        byte[] bytes,
        String filename,
        String contentType,
        String format,
        String extension,
        int width,
        int height,
        boolean converted
    ) {
        public long fileSize() {
            return bytes.length;
        }
    }
}
