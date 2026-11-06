package com.openimage.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openimage.domain.entity.ImageTaskEntity;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class DomesticCommerceOverlayRenderer {
    private final ObjectMapper json;
    private final Font baseFont;

    public DomesticCommerceOverlayRenderer(ObjectMapper json) {
        this.json = json;
        this.baseFont = chooseFont();
    }

    public byte[] renderIfNeeded(byte[] bytes, ImageTaskEntity task) throws Exception {
        if (bytes == null || bytes.length == 0 || task == null) {
            return bytes;
        }
        JsonNode snapshot = readSnapshot(task);
        if (!"system_overlay".equals(snapshot.path("text_mode").asText(""))) {
            return bytes;
        }
        boolean commerceSuite = "commerce_suite".equals(snapshot.path("taskKind").asText(""));
        String module = snapshot.path("module").asText("");
        if (!commerceSuite && (!"creative".equals(task.getImageType()) || !module.contains("国内电商"))) {
            return bytes;
        }
        JsonNode overlay = overlayNode(snapshot);
        if (overlay.isMissingNode() || overlay.isNull() || !overlay.path("enabled").asBoolean(false)) {
            return bytes;
        }
        JsonNode textLayer = overlay.path("text_layer");
        if (textLayer.isMissingNode() || textLayer.isNull()) {
            return bytes;
        }

        BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
        if (source == null) {
            return bytes;
        }
        BufferedImage output = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        applyQuality(g);
        g.drawImage(source, 0, 0, null);
        drawDomesticOverlay(g, output.getWidth(), output.getHeight(), overlay, snapshot);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(output, "png", out);
        return out.toByteArray();
    }

    private void drawDomesticOverlay(Graphics2D g, int width, int height, JsonNode overlay, JsonNode snapshot) {
        JsonNode textLayer = overlay.path("text_layer");
        String platform = overlay.path("platform").asText("");
        String usage = overlay.path("usage").asText("");
        String layout = text(textLayer.path("layout"));
        String position = text(textLayer.path("position"));
        if ("none".equals(position)) {
            return;
        }
        String title = firstText(text(textLayer.path("title")), snapshot.path("productName").asText(""));
        String subtitle = text(textLayer.path("subtitle"));
        String price = text(textLayer.path("price_text"));
        String badge = firstText(text(textLayer.path("badge_text")), text(textLayer.path("badge")));
        String footer = text(textLayer.path("footer_text"));
        List<String> points = textList(textLayer.path("selling_points"));

        int base = Math.max(16, Math.min(width, height));
        int margin = Math.max(38, base / 24);
        int panelW = Math.max(base / 3, (int) (width * 0.38));
        int panelH = height - margin * 2;
        int panelX = width - panelW - margin;
        int panelY = margin;
        if ("bottom".equals(position) || "mobile_cover".equals(layout)) {
            panelW = width - margin * 2;
            panelH = Math.max(base / 3, (int) (height * 0.34));
            panelX = margin;
            panelY = height - panelH - margin;
        } else if ("top".equals(position) || "split_compare".equals(layout)) {
            panelW = width - margin * 2;
            panelH = Math.max(base / 4, (int) (height * 0.28));
            panelX = margin;
            panelY = margin;
        } else if ("left".equals(position)) {
            panelX = margin;
        }

        if (usage.contains("直播") || platform.contains("拼多多") || platform.contains("TikTok") || platform.contains("抖音")) {
            drawEnergeticPanel(g, panelX, panelY, panelW, panelH, platform);
        } else if (platform.contains("小红书")) {
            drawNotePanel(g, panelX, panelY, panelW, panelH);
        } else {
            drawSoftPanel(g, panelX, panelY, panelW, panelH);
        }

        int x = panelX + Math.max(22, panelW / 11);
        int y = panelY + Math.max(46, panelH / 11);
        int contentW = panelW - (x - panelX) * 2;

        if (!badge.isBlank()) {
            int badgeFont = Math.max(22, base / 31);
            g.setFont(font(Font.BOLD, badgeFont));
            FontMetrics fm = g.getFontMetrics();
            int badgeW = Math.min(panelW - 36, fm.stringWidth(badge) + badgeFont * 2);
            int badgeH = badgeFont * 2;
            int bx = panelX + panelW - badgeW - Math.max(16, panelW / 18);
            int by = panelY + Math.max(16, panelH / 30);
            g.setColor(new Color(255, 82, 126, 238));
            g.fillRoundRect(bx, by, badgeW, badgeH, badgeH, badgeH);
            g.setColor(Color.WHITE);
            g.drawString(ellipsize(g, badge, badgeW - badgeFont), bx + badgeFont, by + badgeFont + fm.getAscent() / 2 - 2);
        }

        if (!title.isBlank()) {
            int titleSize = Math.max(36, base / 13);
            g.setFont(font(Font.BOLD, titleSize));
            g.setColor(new Color(44, 31, 45));
            y = drawWrapped(g, title, x, y, contentW, titleSize + 8, 2);
            y += Math.max(10, base / 80);
        }

        if (!subtitle.isBlank()) {
            int subtitleSize = Math.max(22, base / 28);
            g.setFont(font(Font.PLAIN, subtitleSize));
            g.setColor(new Color(96, 70, 89));
            y = drawWrapped(g, subtitle, x, y, contentW, subtitleSize + 9, 2);
            y += Math.max(18, base / 44);
        }

        for (String point : points.stream().filter(item -> !item.isBlank()).limit(3).toList()) {
            int pillH = Math.max(42, base / 18);
            int gap = Math.max(14, base / 60);
            g.setColor(new Color(255, 255, 255, 238));
            g.fillRoundRect(x, y, contentW, pillH, pillH, pillH);
            g.setColor(new Color(255, 135, 168, 245));
            g.setStroke(new BasicStroke(Math.max(2, base / 360)));
            g.drawRoundRect(x, y, contentW, pillH, pillH, pillH);
            g.setFont(font(Font.BOLD, Math.max(20, base / 34)));
            g.setColor(new Color(62, 43, 62));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("• " + ellipsize(g, point, contentW - pillH), x + pillH / 2, y + (pillH - fm.getHeight()) / 2 + fm.getAscent());
            y += pillH + gap;
        }

        if (!price.isBlank()) {
            int priceSize = Math.max(30, base / 18);
            g.setFont(font(Font.BOLD, priceSize));
            g.setColor(new Color(224, 49, 88));
            drawWrapped(g, price, x, Math.min(panelY + panelH - priceSize * 2, y + priceSize), contentW, priceSize + 8, 1);
        }
        if (!footer.isBlank()) {
            int footerSize = Math.max(16, base / 42);
            g.setFont(font(Font.PLAIN, footerSize));
            g.setColor(new Color(96, 102, 118));
            drawWrapped(g, footer, x, panelY + panelH - footerSize * 2, contentW, footerSize + 4, 1);
        }
    }

    private void drawSoftPanel(Graphics2D g, int x, int y, int width, int height) {
        g.setComposite(AlphaComposite.SrcOver.derive(0.88f));
        g.setColor(new Color(255, 246, 249));
        g.fill(new RoundRectangle2D.Float(x, y, width, height, 34, 34));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(255, 184, 205, 150));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(x, y, width, height, 34, 34);
    }

    private void drawNotePanel(Graphics2D g, int x, int y, int width, int height) {
        g.setComposite(AlphaComposite.SrcOver.derive(0.84f));
        g.setColor(new Color(255, 255, 255));
        g.fill(new RoundRectangle2D.Float(x, y, width, height, 32, 32));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(new Color(255, 102, 143, 180));
        g.setStroke(new BasicStroke(3f));
        g.drawRoundRect(x, y, width, height, 32, 32);
    }

    private void drawEnergeticPanel(Graphics2D g, int x, int y, int width, int height, String platform) {
        g.setComposite(AlphaComposite.SrcOver.derive(0.90f));
        g.setColor(platform.contains("拼多多") ? new Color(255, 245, 241) : new Color(244, 249, 255));
        g.fill(new RoundRectangle2D.Float(x, y, width, height, 30, 30));
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(platform.contains("拼多多") ? new Color(255, 93, 78, 180) : new Color(72, 129, 255, 170));
        g.setStroke(new BasicStroke(4f));
        g.drawRoundRect(x, y, width, height, 30, 30);
    }

    private int drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, int lineHeight, int maxLines) {
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = wrap(g, text, maxWidth, maxLines);
        for (String line : lines) {
            g.drawString(line, x, y + fm.getAscent());
            y += lineHeight;
        }
        return y;
    }

    private List<String> wrap(Graphics2D g, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String next = line + String.valueOf(ch);
            if (!line.isEmpty() && g.getFontMetrics().stringWidth(next) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(String.valueOf(ch));
                if (lines.size() == maxLines) {
                    String last = lines.remove(lines.size() - 1);
                    lines.add(ellipsize(g, last, maxWidth));
                    return lines;
                }
            } else {
                line.append(ch);
            }
        }
        if (!line.isEmpty() && lines.size() < maxLines) {
            lines.add(ellipsize(g, line.toString(), maxWidth));
        }
        return lines;
    }

    private String ellipsize(Graphics2D g, String text, int maxWidth) {
        if (text == null || g.getFontMetrics().stringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String value = text;
        while (value.length() > 1 && g.getFontMetrics().stringWidth(value + "...") > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "...";
    }

    private JsonNode overlayNode(JsonNode snapshot) {
        JsonNode direct = snapshot.path("domesticOverlay");
        if (!direct.isMissingNode() && !direct.isNull()) {
            return direct;
        }
        JsonNode commerce = snapshot.path("commerceOverlay");
        if (!commerce.isMissingNode() && !commerce.isNull()) {
            return commerce;
        }
        String prompt = snapshot.path("confirmedFacts").path("prompt").asText("");
        if (prompt.isBlank()) {
            prompt = snapshot.path("brief").asText("");
        }
        String jsonText = extractOverlayJson(prompt);
        if (jsonText.isBlank()) {
            return json.missingNode();
        }
        try {
            return json.readTree(jsonText);
        } catch (Exception ignored) {
            return json.missingNode();
        }
    }

    private String extractOverlayJson(String text) {
        if (text == null) {
            return "";
        }
        int marker = text.indexOf("文字层 JSON:");
        if (marker < 0) {
            return "";
        }
        int start = text.indexOf('{', marker);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    private JsonNode readSnapshot(ImageTaskEntity task) {
        try {
            return json.readTree(task.getPromptSnapshot());
        } catch (Exception ignored) {
            return json.missingNode();
        }
    }

    private List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = text(item);
            if (!value.isBlank()) {
                values.add(value);
            }
        });
        return values;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").trim();
    }

    private Font font(int style, int size) {
        return baseFont.deriveFont(style, (float) size);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private Font chooseFont() {
        Font bundled = loadBundledFont();
        if (bundled != null) {
            return bundled;
        }
        Set<String> installed = new HashSet<>(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT)));
        for (String candidate : List.of(
            "Noto Sans CJK SC",
            "Noto Sans CJK",
            "Source Han Sans SC",
            "Microsoft YaHei",
            "PingFang SC",
            "WenQuanYi Micro Hei",
            "SimHei",
            "SansSerif"
        )) {
            if ("SansSerif".equals(candidate) || installed.contains(candidate)) {
                return new Font(candidate, Font.PLAIN, 16);
            }
        }
        return new Font("SansSerif", Font.PLAIN, 16);
    }

    private Font loadBundledFont() {
        try (InputStream in = DomesticCommerceOverlayRenderer.class.getResourceAsStream("/fonts/NotoSansCJKsc-Regular.otf")) {
            if (in == null) {
                return null;
            }
            Font font = Font.createFont(Font.TRUETYPE_FONT, in);
            GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
            return font.deriveFont(Font.PLAIN, 16f);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }
}
