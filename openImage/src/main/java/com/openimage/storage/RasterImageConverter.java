package com.openimage.storage;

public interface RasterImageConverter {
    ConversionResult convertToJpeg(byte[] source, String sourceFormat, long maxPixels);

    record ConversionResult(byte[] jpegBytes) {}
}
