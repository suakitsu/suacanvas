package com.openimage.storage;

public interface HeifImageConverter {
    ConversionResult convert(byte[] source, long maxPixels);

    record ConversionResult(byte[] jpegBytes, int sourceWidth, int sourceHeight) {}
}
