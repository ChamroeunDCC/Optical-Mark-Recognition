package com.dip.omr.core;

import com.dip.omr.model.ExamConfig;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * SheetGenerator
 * --------------
 * Draws a blank, printable answer sheet (with corner markers + a bubble
 * grid laid out by BubbleGridLayout), and can also produce a synthetic
 * "filled and photographed" version for testing: bubbles are filled in
 * according to a given set of student answers, then the whole sheet is
 * placed onto a larger canvas with a rotation/perspective jitter and
 * mild noise, simulating a real phone photo of a printed sheet on a desk.
 */
public final class SheetGenerator {

    private SheetGenerator() { }

    public static BufferedImage generateBlankTemplate(ExamConfig config, String title) {
        return render(config, title, null);
    }

    public static BufferedImage generateFilledTemplate(ExamConfig config, String title, char[] studentAnswers) {
        return render(config, title, studentAnswers);
    }

    private static BufferedImage render(ExamConfig config, String title, char[] studentAnswers) {
        BufferedImage img = new BufferedImage(BubbleGridLayout.CANVAS_W, BubbleGridLayout.CANVAS_H,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);

        // corner markers
        int m = BubbleGridLayout.MARKER_SIZE;
        for (Point2D.Double c : BubbleGridLayout.cornerMarkerCenters()) {
            g.fillRect((int) (c.x - m / 2.0), (int) (c.y - m / 2.0), m, m);
        }

        // title
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        g.drawString(title, BubbleGridLayout.CONTENT_MARGIN, 100);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.drawString("Fill each bubble completely with a dark pen or pencil.",
                BubbleGridLayout.CONTENT_MARGIN, 130);

        // column choice-letter headers
        int cols = BubbleGridLayout.numColumns(config);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        for (int col = 0; col < cols; col++) {
            for (int c = 0; c < config.numChoices; c++) {
                Point2D.Double p = BubbleGridLayout.bubbleCenter(config, col * BubbleGridLayout.ROWS_PER_COLUMN, c);
                g.drawString(String.valueOf(ExamConfig.choiceLabel(c)), (float) p.x - 4, (float) (BubbleGridLayout.HEADER_Y - 15));
            }
        }

        // question labels + bubbles
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        int r = BubbleGridLayout.BUBBLE_RADIUS;
        for (int q = 0; q < config.numQuestions; q++) {
            Point2D.Double labelPos = BubbleGridLayout.questionLabelAnchor(config, q);
            g.drawString("Q" + (q + 1), (float) labelPos.x, (float) labelPos.y + 5);

            for (int c = 0; c < config.numChoices; c++) {
                Point2D.Double p = BubbleGridLayout.bubbleCenter(config, q, c);
                Ellipse2D circle = new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r);
                boolean filled = studentAnswers != null
                        && q < studentAnswers.length
                        && ExamConfig.choiceIndex(studentAnswers[q]) == c;
                if (filled) {
                    g.fill(circle);
                } else {
                    g.draw(circle);
                }
            }
        }

        g.dispose();
        return img;
    }

    /**
     * Simulates a phone photo: places the (already filled) sheet onto a larger
     * gray "desk background" canvas at a random rotation + slight perspective
     * skew, then adds mild pixel noise. Used to self-test the corner-detection
     * and perspective-correction pipeline end-to-end.
     */
    public static BufferedImage simulatePhoto(BufferedImage sheet, long seed) {
        Random rnd = new Random(seed);
        int pad = 160;
        int outW = sheet.getWidth() + 2 * pad;
        int outH = sheet.getHeight() + 2 * pad;

        BufferedImage photo = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = photo.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(new Color(90, 90, 95)); // desk background
        g.fillRect(0, 0, outW, outH);

        double angleDeg = (rnd.nextDouble() - 0.5) * 6.0; // +-3 degrees
        double scale = 0.92 + rnd.nextDouble() * 0.06;

        AffineTransform t = new AffineTransform();
        t.translate(outW / 2.0, outH / 2.0);
        t.rotate(Math.toRadians(angleDeg));
        t.scale(scale, scale);
        t.translate(-sheet.getWidth() / 2.0, -sheet.getHeight() / 2.0);

        g.drawImage(sheet, t, null);
        g.dispose();

        // mild sensor noise
        for (int i = 0; i < (outW * outH) / 20; i++) {
            int x = rnd.nextInt(outW), y = rnd.nextInt(outH);
            int delta = rnd.nextInt(21) - 10;
            int rgb = photo.getRGB(x, y);
            int rr = clamp(((rgb >> 16) & 0xFF) + delta);
            int gg = clamp(((rgb >> 8) & 0xFF) + delta);
            int bb = clamp((rgb & 0xFF) + delta);
            photo.setRGB(x, y, (rr << 16) | (gg << 8) | bb);
        }
        return photo;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
}
