package com.dip.omr.model;

import java.util.List;

/**
 * GradingResult
 * -------------
 * Per-question outcome: what the student appears to have marked
 * ('A'..'F', '-' for blank, '*' for multiple/ambiguous marks),
 * whether it matches the answer key, and the raw per-choice darkness
 * ratios (useful for debugging / tuning the fill threshold).
 */
public class GradingResult {
    public final int questionIndex; // 0-based
    public final char detectedAnswer;
    public final char correctAnswer;
    public final boolean isCorrect;
    public final double[] fillRatios; // one per choice, 0.0 (empty) to 1.0 (fully filled)

    public GradingResult(int questionIndex, char detectedAnswer, char correctAnswer,
                          boolean isCorrect, double[] fillRatios) {
        this.questionIndex = questionIndex;
        this.detectedAnswer = detectedAnswer;
        this.correctAnswer = correctAnswer;
        this.isCorrect = isCorrect;
        this.fillRatios = fillRatios;
    }

    /** Aggregate result for the whole sheet. */
    public static class OMRResult {
        public final List<GradingResult> perQuestion;
        public final int score;
        public final int total;

        public OMRResult(List<GradingResult> perQuestion) {
            this.perQuestion = perQuestion;
            this.total = perQuestion.size();
            int correct = 0;
            for (GradingResult r : perQuestion) if (r.isCorrect) correct++;
            this.score = correct;
        }

        public double percentage() {
            return total == 0 ? 0.0 : (100.0 * score / total);
        }
    }
}
