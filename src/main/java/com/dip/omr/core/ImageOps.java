package com.dip.omr.core;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ImageOps
 * --------
 * Low-level, reusable image processing building blocks:
 *  - grayscale conversion
 *  - Otsu's automatic thresholding (same algorithm family as classical DIP
 *    document binarization)
 *  - connected-component search (flood fill / BFS) used to locate the
 *    solid black corner markers printed on the answer sheet
 *  - bilinear pixel sampling, used when warping the photo during
 *    perspective correction
 */
public final class ImageOps {

    private ImageOps() { }

    public static int[][] toGrayscale(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[][] gray = new int[w][h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                gray[x][y] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return gray;
    }

    /** Otsu's method: finds the threshold that best separates dark ink from light paper. */
    public static int otsuThreshold(int[][] gray) {
        int w = gray.length, h = gray[0].length;
        int[] hist = new int[256];
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                hist[gray[x][y]]++;

        int total = w * h;
        double sumAll = 0;
        for (int i = 0; i < 256; i++) sumAll += i * hist[i];

        double sumB = 0;
        int wB = 0;
        double maxVar = 0;
        int threshold = 128;
        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;
            sumB += t * hist[t];
            double mB = sumB / wB;
            double mF = (sumAll - sumB) / wF;
            double var = (double) wB * wF * (mB - mF) * (mB - mF);
            if (var > maxVar) { maxVar = var; threshold = t; }
        }
        return threshold;
    }

    /** True where the pixel is "ink" (darker than threshold). */
    public static boolean[][] binarizeDark(int[][] gray, int threshold) {
        int w = gray.length, h = gray[0].length;
        boolean[][] dark = new boolean[w][h];
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++)
                dark[x][y] = gray[x][y] < threshold;
        return dark;
    }

    /**
     * Finds the largest connected dark blob whose centroid falls inside
     * `searchRegion` AND whose area is within [minArea, maxArea], using
     * iterative BFS flood fill (avoids recursion stack overflow on large
     * images). The upper bound lets the caller reject implausibly large
     * blobs (e.g. a misclassified background region) in favor of a
     * smaller blob that actually matches the expected marker size.
     * Returns null if no qualifying blob is found.
     */
    public static Point2D.Double findLargestDarkBlobCentroid(boolean[][] dark, Rectangle searchRegion,
                                                               int minArea, int maxArea) {
        int w = dark.length, h = dark[0].length;
        int x0 = Math.max(0, searchRegion.x);
        int y0 = Math.max(0, searchRegion.y);
        int x1 = Math.min(w, searchRegion.x + searchRegion.width);
        int y1 = Math.min(h, searchRegion.y + searchRegion.height);

        boolean[][] visited = new boolean[w][h];
        long bestArea = 0;
        double bestCx = 0, bestCy = 0;
        boolean found = false;

        for (int sx = x0; sx < x1; sx++) {
            for (int sy = y0; sy < y1; sy++) {
                if (!dark[sx][sy] || visited[sx][sy]) continue;

                long area = 0;
                double sumX = 0, sumY = 0;
                Deque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[]{sx, sy});
                visited[sx][sy] = true;

                while (!stack.isEmpty()) {
                    int[] p = stack.pop();
                    int px = p[0], py = p[1];
                    area++;
                    sumX += px;
                    sumY += py;
                    int[][] neighbors = {{px+1,py},{px-1,py},{px,py+1},{px,py-1}};
                    for (int[] n : neighbors) {
                        int nx = n[0], ny = n[1];
                        if (nx >= x0 && nx < x1 && ny >= y0 && ny < y1
                                && !visited[nx][ny] && dark[nx][ny]) {
                            visited[nx][ny] = true;
                            stack.push(new int[]{nx, ny});
                        }
                    }
                }

                if (area >= minArea && area <= maxArea && area > bestArea) {
                    bestArea = area;
                    bestCx = sumX / area;
                    bestCy = sumY / area;
                    found = true;
                }
            }
        }

        if (!found) return null;
        return new Point2D.Double(bestCx, bestCy);
    }

    /** Bilinear-interpolated pixel sample at fractional coordinates (x,y). Returns gray 0-255. */
    public static double sampleBilinear(int[][] gray, double x, double y) {
        int w = gray.length, h = gray[0].length;
        if (x < 0) x = 0; if (y < 0) y = 0;
        if (x > w - 1) x = w - 1;
        if (y > h - 1) y = h - 1;

        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        int x1 = Math.min(w - 1, x0 + 1), y1 = Math.min(h - 1, y0 + 1);
        double dx = x - x0, dy = y - y0;

        double top = gray[x0][y0] * (1 - dx) + gray[x1][y0] * dx;
        double bottom = gray[x0][y1] * (1 - dx) + gray[x1][y1] * dx;
        return top * (1 - dy) + bottom * dy;
    }

    public static BufferedImage grayArrayToImage(int[][] gray) {
        int w = gray.length, h = gray[0].length;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                int v = Math.max(0, Math.min(255, gray[x][y]));
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        return img;
    }
}
