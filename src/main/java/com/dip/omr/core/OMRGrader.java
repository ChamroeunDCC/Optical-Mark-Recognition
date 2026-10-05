package com.dip.omr.core;

import com.dip.omr.model.ExamConfig;
import com.dip.omr.model.GradingResult;
import com.dip.omr.model.GradingResult.OMRResult;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * OMRGrader
 * ---------
 * The end-to-end pipeline:
 *   1. locateCorners()   - find the 4 black corner markers in a raw photo
 *   2. warpToCanonical() - perspective-correct the photo using those corners
 *   3. grade()           - sample each bubble's darkness and decide answers
 *   4. renderOverlay()   - draw a visual, graded version for the user to check
 */
public final class OMRGrader {

    private OMRGrader() { }

    public static final double FILL_THRESHOLD = 0.35;   // min ink ratio to count as "marked"
    public static final double AMBIGUITY_MARGIN = 0.12;  // winner must beat runner-up by this much
    public static final int MARKER_DARK_THRESHOLD = 100; // corner markers must be darker than this (0-255)

    /** Result of corner detection, or null if any corner wasn't found. */
    public static Point2D.Double[] locateCorners(BufferedImage photo) {
        int[][] gray = ImageOps.toGrayscale(photo);
        // Corner markers are printed near-black ink; using a strict absolute
        // threshold (rather than a global Otsu split) avoids misclassifying
        // a mid-gray background/desk surface as "dark" when the photo
        // contains a lot of non-paper background around the sheet.
        boolean[][] dark = ImageOps.binarizeDark(gray, MARKER_DARK_THRESHOLD);

        int w = photo.getWidth(), h = photo.getHeight();
        int qw = w / 3, qh = h / 3; // search each corner within the outer third of the image
        int minArea = 40;   // markers should be a reasonably sized solid blob, not noise specks
        int maxArea = (w * h) / 20; // reject huge blobs (e.g. a misclassified background region)

        Rectangle topLeftR = new Rectangle(0, 0, qw, qh);
        Rectangle topRightR = new Rectangle(w - qw, 0, qw, qh);
        Rectangle bottomLeftR = new Rectangle(0, h - qh, qw, qh);
        Rectangle bottomRightR = new Rectangle(w - qw, h - qh, qw, qh);

        Point2D.Double tl = ImageOps.findLargestDarkBlobCentroid(dark, topLeftR, minArea, maxArea);
        Point2D.Double tr = ImageOps.findLargestDarkBlobCentroid(dark, topRightR, minArea, maxArea);
        Point2D.Double bl = ImageOps.findLargestDarkBlobCentroid(dark, bottomLeftR, minArea, maxArea);
        Point2D.Double br = ImageOps.findLargestDarkBlobCentroid(dark, bottomRightR, minArea, maxArea);

        if (tl == null || tr == null || bl == null || br == null) return null;
        return new Point2D.Double[]{tl, tr, bl, br};
    }

    /** Perspective-corrects the photo so its 4 corners land on BubbleGridLayout's fixed marker positions. */
    public static int[][] warpToCanonical(BufferedImage photo, Point2D.Double[] photoCorners) {
        Point2D.Double[] canonicalCorners = BubbleGridLayout.cornerMarkerCenters();
        // homography mapping CANONICAL -> PHOTO (so we can backward-sample from the photo)
        PerspectiveTransform canonicalToPhoto =
                PerspectiveTransform.fromPointCorrespondences(canonicalCorners, photoCorners);

        int[][] photoGray = ImageOps.toGrayscale(photo);
        return PerspectiveTransform.warp(photoGray, canonicalToPhoto,
                BubbleGridLayout.CANVAS_W, BubbleGridLayout.CANVAS_H);
    }

    /** Grades a canonical (already perspective-corrected) grayscale sheet against the answer key. */
    public static OMRResult grade(int[][] canonicalGray, ExamConfig config) {
        int threshold = ImageOps.otsuThreshold(canonicalGray);
        boolean[][] dark = ImageOps.binarizeDark(canonicalGray, threshold);

        List<GradingResult> results = new ArrayList<>();
        int radius = BubbleGridLayout.BUBBLE_RADIUS - 2; // sample slightly inside the drawn circle

        for (int q = 0; q < config.numQuestions; q++) {
            double[] fillRatios = new double[config.numChoices];
            for (int c = 0; c < config.numChoices; c++) {
                Point2D.Double center = BubbleGridLayout.bubbleCenter(config, q, c);
                fillRatios[c] = darkRatioInCircle(dark, center.x, center.y, radius);
            }

            char detected = decideAnswer(fillRatios);
            char correct = q < config.answerKey.length ? config.answerKey[q] : '-';
            boolean isCorrect = detected != '-' && detected != '*' && detected == correct;

            results.add(new GradingResult(q, detected, correct, isCorrect, fillRatios));
        }
        return new OMRResult(results);
    }

    private static char decideAnswer(double[] fillRatios) {
        int best = -1, second = -1;
        for (int c = 0; c < fillRatios.length; c++) {
            if (best == -1 || fillRatios[c] > fillRatios[best]) { second = best; best = c; }
            else if (second == -1 || fillRatios[c] > fillRatios[second]) { second = c; }
        }
        if (fillRatios[best] < FILL_THRESHOLD) return '-'; // nothing filled in
        double secondVal = second == -1 ? 0.0 : fillRatios[second];
        if (fillRatios[best] - secondVal < AMBIGUITY_MARGIN) return '*'; // too close to call / multiple marks
        return ExamConfig.choiceLabel(best);
    }

    private static double darkRatioInCircle(boolean[][] dark, double cx, double cy, int radius) {
        int w = dark.length, h = dark[0].length;
        int total = 0, darkCount = 0;
        int x0 = Math.max(0, (int) (cx - radius)), x1 = Math.min(w - 1, (int) (cx + radius));
        int y0 = Math.max(0, (int) (cy - radius)), y1 = Math.min(h - 1, (int) (cy + radius));
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                double dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= radius * radius) {
                    total++;
                    if (dark[x][y]) darkCount++;
                }
            }
        }
        return total == 0 ? 0.0 : (double) darkCount / total;
    }

    /** Draws a graded overlay on top of the canonical sheet: green ring = correct, red ring = student's wrong mark. */
    public static BufferedImage renderOverlay(int[][] canonicalGray, ExamConfig config, OMRResult result) {
        BufferedImage img = ImageOps.grayArrayToImage(canonicalGray);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(3f));
        int r = BubbleGridLayout.BUBBLE_RADIUS + 4;

        for (GradingResult gr : result.perQuestion) {
            int correctIdx = ExamConfig.choiceIndex(gr.correctAnswer);
            if (correctIdx >= 0 && correctIdx < config.numChoices) {
                Point2D.Double p = BubbleGridLayout.bubbleCenter(config, gr.questionIndex, correctIdx);
                g.setColor(new Color(0, 170, 0));
                g.drawOval((int) (p.x - r), (int) (p.y - r), 2 * r, 2 * r);
            }
            if (!gr.isCorrect && gr.detectedAnswer != '-' && gr.detectedAnswer != '*') {
                int wrongIdx = ExamConfig.choiceIndex(gr.detectedAnswer);
                Point2D.Double p = BubbleGridLayout.bubbleCenter(config, gr.questionIndex, wrongIdx);
                g.setColor(new Color(210, 0, 0));
                g.drawOval((int) (p.x - r), (int) (p.y - r), 2 * r, 2 * r);
            }
        }
        g.dispose();
        return img;
    }
}
