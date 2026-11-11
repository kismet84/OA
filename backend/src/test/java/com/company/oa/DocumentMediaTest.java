package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentMediaTest {
  @TempDir Path dir;
  DocumentMedia media = new DocumentMedia();
  String mark = "张三 · E1001 · 2026-11-12";

  void assertWatermarked(BufferedImage image) {
    int red = 0;
    for (int y = 0; y < image.getHeight() - 54; y++)
      for (int x = 0; x < image.getWidth(); x++) {
        var c = new Color(image.getRGB(x, y));
        if (c.getRed() > c.getGreen() + 10 && c.getRed() > c.getBlue() + 10) red++;
      }
    assertTrue(red > 100, "水印必须烧录在内容区域");
  }

  @Test
  void pdfPagesAndExportHaveBakedPixelsWithoutOriginalStreams() throws Exception {
    Path file = dir.resolve("input.pdf");
    try (var pdf = new PDDocument()) {
      pdf.addPage(new PDPage(PDRectangle.A4));
      pdf.addPage(new PDPage(PDRectangle.A4));
      pdf.save(file.toFile());
    }
    assertEquals(2, media.pages(file, "application/pdf"));
    assertWatermarked(
        ImageIO.read(new ByteArrayInputStream(media.preview(file, "application/pdf", 2, mark))));
    byte[] out = media.download(file, "application/pdf", 2, mark);
    try (var pdf = Loader.loadPDF(out)) {
      assertEquals(2, pdf.getNumberOfPages());
      assertEquals(mark, pdf.getDocumentInformation().getSubject());
      for (int i = 0; i < 2; i++) {
        assertTrue(pdf.getPage(i).getResources().getXObjectNames().iterator().hasNext());
        assertWatermarked(new PDFRenderer(pdf).renderImage(i));
      }
    }
  }

  @Test
  void imagesAreRenderedAndViewerIdentityChangesPixels() throws Exception {
    Path file = dir.resolve("input.png");
    var image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
    var g = image.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, 800, 600);
    g.dispose();
    ImageIO.write(image, "png", file.toFile());
    assertEquals(1, media.pages(file, "image/png"));
    byte[] first = media.preview(file, "image/png", 1, mark);
    assertWatermarked(ImageIO.read(new ByteArrayInputStream(first)));
    assertFalse(
        java.util.Arrays.equals(
            first, media.preview(file, "image/png", 1, "李四 · E1002 · 2026-11-12")));
    assertArrayEquals(first, media.download(file, "image/png", 1, mark));
    var longName =
        ImageIO.read(
            new ByteArrayInputStream(
                media.preview(
                    file, "image/png", 1, "很长的姓名".repeat(30) + " · E123456789 · 2026-11-12")));
    assertTrue(longName.getHeight() > 654, "长姓名水印应换行，不能裁掉工号或日期");
  }

  @Test
  void invalidAndEncryptedFilesAreRejected() throws Exception {
    var missing =
        assertThrows(ApiException.class, () -> media.pages(dir.resolve("missing"), "image/png"));
    assertEquals(400, missing.status);
    Path broken = dir.resolve("broken");
    Files.writeString(broken, "%PDF-broken");
    assertThrows(ApiException.class, () -> media.pages(broken, "application/pdf"));
    try (var pdf = new PDDocument()) {
      pdf.addPage(new PDPage());
      pdf.protect(
          new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
              "owner", "password", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
      pdf.save(broken.toFile());
    }
    assertThrows(ApiException.class, () -> media.pages(broken, "application/pdf"));
  }
}
