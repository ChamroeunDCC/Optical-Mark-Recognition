package com.dip.omr.core;

import com.dip.omr.model.ExamConfig;

import java.awt.geom.Point2D;

/**
 * BubbleGridLayout
 * ----------------
 * The single source of truth for where every bubble and every corner
 * marker sits on the "canonical" (perspective-corrected) sheet image.
 * SheetGenerator draws bubbles at these exact coordinates; OMRGrader
 * samples darkness at these exact coordinates on a warped photo. Because
 * both sides read from this one class, the two can never drift apart.
 */
public final class BubbleGridLayout {

    private BubbleGridLayout() { }

    public static final int CANVAS_W = 1000;
    public static final int CANVAS_H = 1400;

    // Corner markers: solid black squares used to locate + perspective-correct the sheet
    public static final int MARKER_SIZE = 34;
    public static final int MARKER_MARGIN = 26; // distance from image edge to marker's near edge

    public static final int HEADER_Y = 190;      // where the question grid starts
    public static final int ROW_HEIGHT = 40;
    public static final int ROWS_PER_COLUMN = 25; // wrap into a new column after this many questions
    public static final int CONTENT_MARGIN = 70;  // left/right margin of the question grid
    public static final int LABEL_WIDTH = 55;     // space reserved for "Q1", "Q2", ... label
    public static final int BUBBLE_SPACING = 46;  // horizontal distance between bubble centers
    public static final int BUBBLE_RADIUS = 11;

    /** The 4 corner marker CENTERS in canonical (warped) image space, in a fixed order:
     *  top-left, top-right, bottom-left, bottom-right. */
    public static Point2D.Double[] cornerMarkerCenters() {
        double half = MARKER_SIZE / 2.0;
        double left = MARKER_MARGIN + half;
        double right = CANVAS_W - MARKER_MARGIN - half;
        double top = MARKER_MARGIN + half;
        double bottom = CANVAS_H - MARKER_MARGIN - half;
        return new Point2D.Double[] {
                new Point2D.Double(left, top),
                new Point2D.Double(right, top),
                new Point2D.Double(left, bottom),
                new Point2D.Double(right, bottom)
        };
    }

    public static int numColumns(ExamConfig config) {
        return (int) Math.ceil(config.numQuestions / (double) ROWS_PER_COLUMN);
    }

    private static double columnWidth(ExamConfig config) {
        int cols = numColumns(config);
        return (CANVAS_W - 2.0 * CONTENT_MARGIN) / cols;
    }

    /** Pixel center of question `q` (0-indexed), choice `c` (0-indexed), in canonical space. */
    public static Point2D.Double bubbleCenter(ExamConfig config, int q, int c) {
        int col = q / ROWS_PER_COLUMN;
        int rowInCol = q % ROWS_PER_COLUMN;
        double colWidth = columnWidth(config);
        double x0 = CONTENT_MARGIN + col * colWidth;
        double y = HEADER_Y + rowInCol * ROW_HEIGHT;
        double x = x0 + LABEL_WIDTH + c * BUBBLE_SPACING;
        return new Point2D.Double(x, y);
    }

    /** Top-left anchor for the "Q<n>" label of question q (0-indexed). */
    public static Point2D.Double questionLabelAnchor(ExamConfig config, int q) {
        int col = q / ROWS_PER_COLUMN;
        int rowInCol = q % ROWS_PER_COLUMN;
        double colWidth = columnWidth(config);
        double x0 = CONTENT_MARGIN + col * colWidth;
        double y = HEADER_Y + rowInCol * ROW_HEIGHT;
        return new Point2D.Double(x0, y);
    }
}
