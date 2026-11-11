package com.company.oa;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

/**
 * All employee bytes are newly rendered; no original PDF streams, layers or image metadata escape.
 */
@Component
class DocumentMedia {
  int pages(Path path, String type) {
    try {
      ApiException.require(
          Files.isRegularFile(path)
              && Files.size(path) > 0
              && Files.size(path) <= 10L * 1024 * 1024,
          "附件文件缺失、为空或超过 10MB");
      if (type.equals("application/pdf")) {
        try (var pdf = Loader.loadPDF(path.toFile())) {
          ApiException.require(!pdf.isEncrypted(), "不支持加密 PDF");
          int count = pdf.getNumberOfPages();
          ApiException.require(count > 0 && count <= 200, "PDF 须为 1 至 200 页");
          for (var page : pdf.getPages()) {
            var box = page.getCropBox();
            ApiException.require(
                box.getWidth() > 0
                    && box.getHeight() > 0
                    && box.getWidth() <= 3000
                    && box.getHeight() <= 3000,
                "PDF 页面尺寸无效或过大");
          }
          return count;
        }
      }
      ApiException.require(
          type.equals("image/png") || type.equals("image/jpeg"), "仅支持 PDF、PNG、JPEG");
      try (var input = ImageIO.createImageInputStream(path.toFile())) {
        var readers = ImageIO.getImageReaders(input);
        ApiException.require(readers.hasNext(), "图片无法解码");
        ImageReader reader = readers.next();
        try {
          reader.setInput(input);
          ApiException.require(
              (long) reader.getWidth(0) * reader.getHeight(0) <= 20_000_000, "图片不得超过 2000 万像素");
          var decoded = reader.read(0);
          ApiException.require(decoded != null, "图片无法解码");
          decoded.flush();
        } finally {
          reader.dispose();
        }
      }
      return 1;
    } catch (IOException | IllegalArgumentException e) {
      throw new ApiException(400, "附件无法预览，请使用有效且未加密的 PDF 或图片");
    }
  }

  BufferedImage render(Path path, String type, int page, String watermark) throws IOException {
    BufferedImage source;
    if (type.equals("application/pdf")) {
      try (var pdf = Loader.loadPDF(path.toFile())) {
        var box = pdf.getPage(page - 1).getCropBox();
        float scale = Math.min(1.7f, 1800f / Math.max(box.getWidth(), box.getHeight()));
        var renderer = new PDFRenderer(pdf);
        renderer.setSubsamplingAllowed(true);
        source = renderer.renderImage(page - 1, scale);
      }
    } else source = ImageIO.read(path.toFile());
    ApiException.require(source != null, "图片无法解码");
    double scale = Math.min(1, 1800.0 / Math.max(source.getWidth(), source.getHeight()));
    int w = Math.max(600, (int) (source.getWidth() * scale));
    int h = Math.max(240, (int) (source.getHeight() * scale));
    var font = new Font("SansSerif", Font.PLAIN, Math.max(16, Math.min(25, w / 38)));
    var probe = source.createGraphics();
    var metrics = probe.getFontMetrics(font);
    var lines = new java.util.ArrayList<String>();
    StringBuilder line = new StringBuilder();
    for (int cp : watermark.codePoints().toArray()) {
      String next = new String(Character.toChars(cp));
      if (!line.isEmpty() && metrics.stringWidth(line + next) > w - 40) {
        lines.add(line.toString());
        line.setLength(0);
      }
      line.append(next);
    }
    lines.add(line.toString());
    int lineHeight = metrics.getHeight();
    probe.dispose();
    int footer = Math.max(54, lines.size() * lineHeight + 24);
    var out = new BufferedImage(w, h + footer, BufferedImage.TYPE_INT_RGB);
    var g = out.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, w, h + footer);
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    int drawWidth = Math.max(1, (int) (source.getWidth() * scale));
    int drawHeight = Math.max(1, (int) (source.getHeight() * scale));
    g.drawImage(source, (w - drawWidth) / 2, (h - drawHeight) / 2, drawWidth, drawHeight, null);
    g.setFont(font);
    // Opaque wrapped footer plus repeated translucent marks across the content.
    g.setColor(new Color(155, 35, 35));
    for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), 16, h + 28 + i * lineHeight);
    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
    for (int y = 45; y < h; y += Math.max(180, footer + 40)) {
      for (int i = 0; i < lines.size(); i++) g.drawString(lines.get(i), 20, y + i * lineHeight);
    }
    g.dispose();
    source.flush();
    return out;
  }

  byte[] preview(Path path, String type, int page, String mark) {
    try {
      var bytes = new ByteArrayOutputStream();
      var image = render(path, type, page, mark);
      ImageIO.write(image, "png", bytes);
      image.flush();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new ApiException(400, "附件无法预览，请联系管理员重新上传");
    }
  }

  byte[] download(Path path, String type, int count, String mark) {
    if (!type.equals("application/pdf")) return preview(path, type, 1, mark);
    try (var out = new PDDocument();
        var bytes = new ByteArrayOutputStream()) {
      for (int i = 1; i <= count; i++) {
        var image = render(path, type, i, mark);
        var page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
        out.addPage(page);
        try (var stream = new PDPageContentStream(out, page)) {
          stream.drawImage(
              LosslessFactory.createFromImage(out, image),
              0,
              0,
              image.getWidth(),
              image.getHeight());
        }
        image.flush();
      }
      out.getDocumentInformation().setSubject(mark);
      out.save(bytes);
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new ApiException(400, "附件无法导出，请联系管理员");
    }
  }
}
