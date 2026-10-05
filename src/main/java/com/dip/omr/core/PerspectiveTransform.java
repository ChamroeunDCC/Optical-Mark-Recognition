package com.dip.omr.core;

import java.awt.geom.Point2D;

/**
 * PerspectiveTransform
 * ---------------------
 * Computes a homography (3x3 projective transform) from 4 point
 * correspondences using the classical Direct Linear Transform (DLT)
 * method, and applies it to warp a photographed (angled) answer sheet
 * back into a flat, canonical rectangle so the bubble grid lines up
 * with BubbleGridLayout's fixed coordinates.
 *
 * This is the same family of technique used in document-scanner apps:
 * 4 known corners in the source photo -> a fixed rectangle in the output.
 */
public final class PerspectiveTransform {

    private final double[] h; // 3x3 matrix, row-major, h[8] == 1

    private PerspectiveTransform(double[] h) {
        this.h = h;
    }

    /** Computes the homography that maps src[i] -> dst[i] for i = 0..3. */
    public static PerspectiveTransform fromPointCorrespondences(Point2D.Double[] src, Point2D.Double[] dst) {
        // Build the 8x8 linear system A*x = b for the 8 unknowns h11..h32 (h33 fixed to 1)
        double[][] A = new double[8][8];
        double[] b = new double[8];

        for (int i = 0; i < 4; i++) {
            double sx = src[i].x, sy = src[i].y;
            double dxp = dst[i].x, dyp = dst[i].y;

            A[2*i][0] = sx; A[2*i][1] = sy; A[2*i][2] = 1;
            A[2*i][3] = 0; A[2*i][4] = 0; A[2*i][5] = 0;
            A[2*i][6] = -sx * dxp; A[2*i][7] = -sy * dxp;
            b[2*i] = dxp;

            A[2*i+1][0] = 0; A[2*i+1][1] = 0; A[2*i+1][2] = 0;
            A[2*i+1][3] = sx; A[2*i+1][4] = sy; A[2*i+1][5] = 1;
            A[2*i+1][6] = -sx * dyp; A[2*i+1][7] = -sy * dyp;
            b[2*i+1] = dyp;
        }

        double[] x = solveLinearSystem(A, b);
        double[] hMat = new double[]{x[0], x[1], x[2], x[3], x[4], x[5], x[6], x[7], 1.0};
        return new PerspectiveTransform(hMat);
    }

    /** Gaussian elimination with partial pivoting for an 8x8 system. */
    private static double[] solveLinearSystem(double[][] A, double[] b) {
        int n = 8;
        double[][] M = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(A[i], 0, M[i], 0, n);
            M[i][n] = b[i];
        }

        for (int col = 0; col < n; col++) {
            int pivot = col;
            for (int row = col + 1; row < n; row++)
                if (Math.abs(M[row][col]) > Math.abs(M[pivot][col])) pivot = row;
            double[] tmp = M[col]; M[col] = M[pivot]; M[pivot] = tmp;

            double pivotVal = M[col][col];
            if (Math.abs(pivotVal) < 1e-12) continue; // degenerate; skip (shouldn't happen for real corners)
            for (int j = col; j <= n; j++) M[col][j] /= pivotVal;

            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = M[row][col];
                for (int j = col; j <= n; j++) M[row][j] -= factor * M[col][j];
            }
        }

        double[] result = new double[n];
        for (int i = 0; i < n; i++) result[i] = M[i][n];
        return result;
    }

    /** Applies this homography to a single point (src -> dst space). */
    public Point2D.Double apply(double x, double y) {
        double denom = h[6] * x + h[7] * y + h[8];
        double dx = (h[0] * x + h[1] * y + h[2]) / denom;
        double dy = (h[3] * x + h[4] * y + h[5]) / denom;
        return new Point2D.Double(dx, dy);
    }

    /** Returns the inverse transform (dst -> src), needed for backward warping. */
    public PerspectiveTransform inverse() {
        double a = h[0], bb = h[1], c = h[2];
        double d = h[3], e = h[4], f = h[5];
        double g = h[6], hh = h[7], i = h[8];

        double det = a * (e * i - f * hh) - bb * (d * i - f * g) + c * (d * hh - e * g);
        double invDet = 1.0 / det;

        double[] inv = new double[9];
        inv[0] = (e * i - f * hh) * invDet;
        inv[1] = (c * hh - bb * i) * invDet;
        inv[2] = (bb * f - c * e) * invDet;
        inv[3] = (f * g - d * i) * invDet;
        inv[4] = (a * i - c * g) * invDet;
        inv[5] = (c * d - a * f) * invDet;
        inv[6] = (d * hh - e * g) * invDet;
        inv[7] = (bb * g - a * hh) * invDet;
        inv[8] = (a * e - bb * d) * invDet;

        // normalize so inv[8] == 1 (consistent with our forward-matrix convention)
        double norm = inv[8];
        for (int k = 0; k < 9; k++) inv[k] /= norm;
        return new PerspectiveTransform(inv);
    }

    /**
     * Warps `srcGray` into a new canonicalW x canonicalH grayscale image using
     * BACKWARD mapping: for each output pixel, find where it came from in the
     * source via `srcFromCanonical` (canonical -> source homography), and
     * bilinearly sample. This avoids holes that forward-warping would leave.
     */
    public static int[][] warp(int[][] srcGray, PerspectiveTransform srcFromCanonical,
                                int canonicalW, int canonicalH) {
        int[][] out = new int[canonicalW][canonicalH];
        for (int x = 0; x < canonicalW; x++) {
            for (int y = 0; y < canonicalH; y++) {
                Point2D.Double sp = srcFromCanonical.apply(x, y);
                out[x][y] = (int) Math.round(ImageOps.sampleBilinear(srcGray, sp.x, sp.y));
            }
        }
        return out;
    }
}
