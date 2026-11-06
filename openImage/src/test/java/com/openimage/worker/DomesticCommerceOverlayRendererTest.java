package com.openimage.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.ImageTaskEntity;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DomesticCommerceOverlayRendererTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DomesticCommerceOverlayRenderer renderer = new DomesticCommerceOverlayRenderer(json);

    @Test
    void bundledFontLoadsAndCoversChineseCommerceText() throws Exception {
        try (var input = getClass().getResourceAsStream("/fonts/NotoSansCJKsc-Regular.otf")) {
            assertNotNull(input);
            Font font = Font.createFont(Font.TRUETYPE_FONT, input);
            assertEquals(-1, font.canDisplayUpTo("中文商品主图 淘宝天猫 新品 ¥99.00 ABC"));
        }
    }

    @Test
    void rendersChineseTextLayerForDomesticCommerceTasks() throws Exception {
        byte[] input = whitePng();
        ImageTaskEntity task = task("""
            {
              "module":"国内电商 / 淘宝/天猫 / 主图",
              "text_mode":"system_overlay",
              "domesticOverlay":{
                "enabled":true,
                "platform":"淘宝/天猫",
                "usage":"主图",
                "text_layer":{
                  "title":"Lolita 兔子玩偶包",
                  "subtitle":"立耳兔和垂耳兔两款",
                  "selling_points":["双款式","轻量出街","萌系搭配"],
                  "price_text":"",
                  "badge":"新品"
                }
              }
            }
            """);

        byte[] output = renderer.renderIfNeeded(input, task);

        assertFalse(Arrays.equals(input, output));
        BufferedImage rendered = ImageIO.read(new ByteArrayInputStream(output));
        assertNotEquals(Color.WHITE.getRGB(), rendered.getRGB(430, 48));
    }

    @Test
    void skipsOverlayForAiRenderedTextTasks() throws Exception {
        byte[] input = whitePng();
        ImageTaskEntity task = task("""
            {
              "module":"国内电商 / 淘宝/天猫 / 主图",
              "text_mode":"ai_short_text",
              "domesticOverlay":{"enabled":true}
            }
            """);

        byte[] output = renderer.renderIfNeeded(input, task);

        assertTrue(Arrays.equals(input, output));
    }

    @Test
    void leavesNonDomesticCreativeImagesUntouched() throws Exception {
        byte[] input = whitePng();
        ImageTaskEntity task = task("""
            {"module":"自由创作","domesticOverlay":{"enabled":true}}
            """);

        byte[] output = renderer.renderIfNeeded(input, task);

        assertTrue(Arrays.equals(input, output));
    }

    private ImageTaskEntity task(String snapshot) {
        ImageTaskEntity task = new ImageTaskEntity();
        task.setImageType("creative");
        task.setPromptSnapshot(snapshot);
        return task;
    }

    private byte[] whitePng() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 512, 512);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
