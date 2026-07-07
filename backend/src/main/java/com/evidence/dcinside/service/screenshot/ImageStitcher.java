package com.evidence.dcinside.service.screenshot;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;

public final class ImageStitcher {

    private ImageStitcher() {
    }

    public static int getImageHeight(byte[] pngBytes) throws IOException {
        return readBufferedImage(pngBytes).getHeight();
    }

    public static byte[] appendVertically(byte[] topImage, byte[] bottomImage) throws IOException {
        BufferedImage top = readBufferedImage(topImage);
        BufferedImage bottom = readBufferedImage(bottomImage);
        int totalWidth = Math.max(top.getWidth(), bottom.getWidth());
        int totalHeight = top.getHeight() + bottom.getHeight();

        BufferedImage combined = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = combined.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, totalWidth, totalHeight);
        graphics.drawImage(top, 0, 0, null);
        graphics.drawImage(bottom, 0, top.getHeight(), null);
        graphics.dispose();

        return writePng(combined);
    }

    public static byte[] stitchHorizontally(List<byte[]> columnImages) throws IOException {
        List<BufferedImage> images = columnImages.stream().map(ImageStitcher::readBufferedImageUnchecked).toList();
        int totalWidth = 0;
        int totalHeight = 0;
        for (BufferedImage image : images) {
            totalWidth += image.getWidth();
            totalHeight = Math.max(totalHeight, image.getHeight());
        }

        BufferedImage combined = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = combined.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, totalWidth, totalHeight);

        int x = 0;
        for (BufferedImage image : images) {
            graphics.drawImage(image, x, 0, null);
            x += image.getWidth();
        }
        graphics.dispose();

        return writePng(combined);
    }

    private static BufferedImage readBufferedImage(byte[] pngBytes) throws IOException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(pngBytes)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IOException("PNG 이미지를 읽을 수 없습니다.");
            }
            return image;
        }
    }

    private static BufferedImage readBufferedImageUnchecked(byte[] pngBytes) {
        try {
            return readBufferedImage(pngBytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] writePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
