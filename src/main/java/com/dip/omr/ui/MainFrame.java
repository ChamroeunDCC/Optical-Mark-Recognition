package com.dip.omr.ui;

import com.dip.omr.core.*;
import com.dip.omr.model.ExamConfig;
import com.dip.omr.model.GradingResult;
import com.dip.omr.model.GradingResult.OMRResult;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * MainFrame
 * ---------
 * Lets the user:
 *   1. configure an exam (question count, choice count, answer key)
 *   2. generate a printable blank answer sheet, or a synthetic
 *      "filled + photographed" sheet for testing the pipeline
 *   3. load a real photo of a filled sheet
 *   4. run the full OMR pipeline (locate corners -> perspective warp -> grade)
 *   5. see a graded overlay and a per-question results table
 *   6. export the results to CSV
 */
public class MainFrame extends JFrame {

    private ExamConfig config;
    private BufferedImage loadedPhoto;
    private int[][] canonicalGray;
    private OMRResult lastResult;

    private final JLabel photoLabel = new JLabel("No photo loaded", SwingConstants.CENTER);
    private final JLabel resultLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel statusLabel = new JLabel("Configure an exam, then generate or load a sheet.");
    private final JLabel scoreLabel = new JLabel("Score: -");

    private final JSpinner questionsSpinner = new JSpinner(new SpinnerNumberModel(20, 1, 100, 1));
    private final JSpinner choicesSpinner = new JSpinner(new SpinnerNumberModel(4, 2, 6, 1));
    private final JTextField answerKeyField = new JTextField("CCDABBCDCADBADCCADAB");

    private final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Q#", "Detected", "Correct", "Result"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable resultsTable = new JTable(tableModel);

    public MainFrame() {
        super("Optical Mark Recognition (OMR) - Bubble Answer Sheet Grader");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1300, 800);
        setLocationRelativeTo(null);

        add(buildTopConfigPanel(), BorderLayout.NORTH);
        add(buildImagesPanel(), BorderLayout.CENTER);
        add(new JScrollPane(buildRightPanel()), BorderLayout.EAST);
        add(statusLabel, BorderLayout.SOUTH);
        statusLabel.setBorder(new EmptyBorder(4, 8, 4, 8));
    }

    // =================================================================
    //  TOP: exam configuration
    // =================================================================
    private JPanel buildTopConfigPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Exam Configuration"));

        panel.add(new JLabel("Questions:"));
        panel.add(questionsSpinner);
        panel.add(new JLabel("Choices per question:"));
        panel.add(choicesSpinner);
        panel.add(new JLabel("Answer Key (e.g. ABCDA...):"));
        answerKeyField.setColumns(24);
        panel.add(answerKeyField);

        JButton applyConfigBtn = new JButton("Apply Configuration");
        applyConfigBtn.addActionListener(e -> applyConfig());
        panel.add(applyConfigBtn);

        return panel;
    }

    private void applyConfig() {
        int numQuestions = (Integer) questionsSpinner.getValue();
        int numChoices = (Integer) choicesSpinner.getValue();
        String keyText = answerKeyField.getText().trim().toUpperCase();

        char[] key = new char[numQuestions];
        for (int i = 0; i < numQuestions; i++) {
            if (i < keyText.length() && ExamConfig.choiceIndex(keyText.charAt(i)) >= 0
                    && ExamConfig.choiceIndex(keyText.charAt(i)) < numChoices) {
                key[i] = keyText.charAt(i);
            } else {
                key[i] = ExamConfig.choiceLabel(0); // default filler if key too short/invalid
            }
        }
        try {
            config = new ExamConfig(numQuestions, numChoices, key);
            statusLabel.setText("Configuration applied: " + numQuestions + " questions, "
                    + numChoices + " choices each.");
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, "Invalid configuration: " + ex.getMessage());
        }
    }

    // =================================================================
    //  CENTER: photo (left) + graded result (right)
    // =================================================================
    private JPanel buildImagesPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 8, 0));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane photoScroll = new JScrollPane(photoLabel);
        photoScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.GRAY), "Loaded Sheet Photo",
                TitledBorder.LEFT, TitledBorder.TOP));

        JScrollPane resultScroll = new JScrollPane(resultLabel);
        resultScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.GRAY), "Graded Result (Perspective-Corrected)",
                TitledBorder.LEFT, TitledBorder.TOP));

        panel.add(photoScroll);
        panel.add(resultScroll);
        return panel;
    }

    // =================================================================
    //  RIGHT: actions + results table
    // =================================================================
    private JPanel buildRightPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setPreferredSize(new Dimension(360, 0));

        panel.add(sectionLabel("1. Get a Sheet"));
        panel.add(actionButton("Generate Blank Template (to print)", e -> generateBlank()));
        panel.add(actionButton("Generate Sample Filled+Photographed Sheet (for testing)", e -> generateSample()));
        panel.add(actionButton("Load Photo of a Filled Sheet...", e -> loadPhoto()));

        panel.add(Box.createVerticalStrut(15));
        panel.add(sectionLabel("2. Grade"));
        panel.add(actionButton("Run OMR: Detect + Warp + Grade", e -> runPipeline()));

        panel.add(Box.createVerticalStrut(15));
        panel.add(sectionLabel("3. Results"));
        scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 16f));
        scoreLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(scoreLabel);

        resultsTable.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(resultsTable);
        tableScroll.setPreferredSize(new Dimension(330, 320));
        tableScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(tableScroll);

        panel.add(actionButton("Export Results to CSV...", e -> exportCsv()));

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(new EmptyBorder(4, 0, 4, 0));
        return label;
    }

    private JButton actionButton(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        button.setMargin(new Insets(6, 8, 6, 8));
        button.addActionListener(listener);
        return button;
    }

    // =================================================================
    //  ACTIONS
    // =================================================================
    private boolean ensureConfig() {
        if (config == null) applyConfig();
        return config != null;
    }

    private void generateBlank() {
        if (!ensureConfig()) return;
        BufferedImage sheet = SheetGenerator.generateBlankTemplate(config, "Answer Sheet");
        photoLabel.setIcon(new ImageIcon(sheet));
        photoLabel.setText(null);
        loadedPhoto = sheet;

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("blank_answer_sheet.png"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIO.write(sheet, "png", chooser.getSelectedFile());
                statusLabel.setText("Blank template saved to " + chooser.getSelectedFile().getAbsolutePath()
                        + ". Print it, fill it in by hand, then photograph it and use 'Load Photo'.");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error saving: " + ex.getMessage());
            }
        }
    }

    private void generateSample() {
        if (!ensureConfig()) return;
        // Build a plausible "student" answer set: matches the key except a few deliberate misses
        char[] student = config.answerKey.clone();
        if (student.length > 2) student[2] = ExamConfig.choiceLabel((ExamConfig.choiceIndex(student[2]) + 1) % config.numChoices);
        if (student.length > 9) student[9] = ExamConfig.choiceLabel((ExamConfig.choiceIndex(student[9]) + 2) % config.numChoices);

        BufferedImage sheet = SheetGenerator.generateFilledTemplate(config, "Answer Sheet (Sample)", student);
        BufferedImage photo = SheetGenerator.simulatePhoto(sheet, System.currentTimeMillis());
        loadedPhoto = photo;
        photoLabel.setIcon(new ImageIcon(photo));
        photoLabel.setText(null);
        statusLabel.setText("Synthetic filled + photographed sample generated. Click 'Run OMR' to grade it.");
    }

    private void loadPhoto() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Image files", "jpg", "jpeg", "png", "bmp"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            BufferedImage img = ImageIO.read(chooser.getSelectedFile());
            if (img == null) {
                JOptionPane.showMessageDialog(this, "Could not read that file as an image.");
                return;
            }
            loadedPhoto = img;
            photoLabel.setIcon(new ImageIcon(img));
            photoLabel.setText(null);
            statusLabel.setText("Loaded: " + chooser.getSelectedFile().getName());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Error loading image: " + ex.getMessage());
        }
    }

    private void runPipeline() {
        if (!ensureConfig()) return;
        if (loadedPhoto == null) {
            JOptionPane.showMessageDialog(this, "Generate or load a sheet photo first.");
            return;
        }

        Point2D.Double[] corners = OMRGrader.locateCorners(loadedPhoto);
        if (corners == null) {
            JOptionPane.showMessageDialog(this,
                    "Could not find all 4 corner markers. Make sure the sheet's black corner\n"
                  + "squares are visible and reasonably well-lit, then try again.");
            statusLabel.setText("Corner detection failed.");
            return;
        }

        canonicalGray = OMRGrader.warpToCanonical(loadedPhoto, corners);
        lastResult = OMRGrader.grade(canonicalGray, config);
        BufferedImage overlay = OMRGrader.renderOverlay(canonicalGray, config, lastResult);

        resultLabel.setIcon(new ImageIcon(overlay));
        resultLabel.setText(null);

        populateTable(lastResult);
        scoreLabel.setText("Score: " + lastResult.score + " / " + lastResult.total
                + "  (" + String.format("%.1f", lastResult.percentage()) + "%)");
        statusLabel.setText("Graded successfully. Green ring = correct answer, red ring = student's wrong mark.");
    }

    private void populateTable(OMRResult result) {
        tableModel.setRowCount(0);
        for (GradingResult r : result.perQuestion) {
            String outcome = r.detectedAnswer == '-' ? "Blank"
                    : r.detectedAnswer == '*' ? "Multiple marks"
                    : r.isCorrect ? "Correct" : "Wrong";
            tableModel.addRow(new Object[]{
                    r.questionIndex + 1,
                    String.valueOf(r.detectedAnswer),
                    String.valueOf(r.correctAnswer),
                    outcome
            });
        }
    }

    private void exportCsv() {
        if (lastResult == null) {
            JOptionPane.showMessageDialog(this, "Run grading first.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("omr_results.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try (FileWriter fw = new FileWriter(chooser.getSelectedFile())) {
            fw.write("Question,Detected,Correct,Result\n");
            for (GradingResult r : lastResult.perQuestion) {
                String outcome = r.detectedAnswer == '-' ? "Blank"
                        : r.detectedAnswer == '*' ? "Multiple"
                        : r.isCorrect ? "Correct" : "Wrong";
                fw.write((r.questionIndex + 1) + "," + r.detectedAnswer + "," + r.correctAnswer + "," + outcome + "\n");
            }
            fw.write("\nScore," + lastResult.score + "/" + lastResult.total + "\n");
            statusLabel.setText("Results exported to " + chooser.getSelectedFile().getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Error exporting CSV: " + ex.getMessage());
        }
    }
}
