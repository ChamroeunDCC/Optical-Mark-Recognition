package com.dip.omr.model;

/**
 * ExamConfig
 * ----------
 * Describes the shape of the answer sheet: how many questions, how many
 * choices per question (A, B, C, D, ...), and the correct answer key.
 * Both SheetGenerator (drawing the template) and OMRGrader (reading a
 * filled sheet) use this same config so bubble positions always agree.
 */
public class ExamConfig {

    public final int numQuestions;
    public final int numChoices; // e.g. 4 means choices A..D
    public final char[] answerKey; // length == numQuestions, values 'A'.., or '-' if unset

    public ExamConfig(int numQuestions, int numChoices, char[] answerKey) {
        if (numQuestions <= 0) throw new IllegalArgumentException("numQuestions must be > 0");
        if (numChoices < 2 || numChoices > 6) throw new IllegalArgumentException("numChoices must be 2-6");
        if (answerKey.length != numQuestions) throw new IllegalArgumentException("answerKey length mismatch");
        this.numQuestions = numQuestions;
        this.numChoices = numChoices;
        this.answerKey = answerKey;
    }

    public static char choiceLabel(int choiceIndex) {
        return (char) ('A' + choiceIndex);
    }

    public static int choiceIndex(char label) {
        return Character.toUpperCase(label) - 'A';
    }
}
