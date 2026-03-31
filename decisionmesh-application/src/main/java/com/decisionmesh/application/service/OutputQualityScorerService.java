package com.decisionmesh.application.service;

import com.decisionmesh.domain.execution.ExecutionRecord;
import com.decisionmesh.domain.intent.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;


import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores AI response quality in the EVALUATING phase.
 *
 * Module:  decisionmesh-intelligence
 * Package: com.decisionmesh.intelligence.evaluation
 *
 * Uses heuristic scoring only — no AdapterRegistry dependency.
 * When you are ready to add an LLM judge call, inject your adapter
 * client directly here and add a scoreWithJudge() path guarded by
 * the app.quality.scorer.enabled config flag.
 *
 * Scores four dimensions:
 *   faithfulness      — does the response address the stated objective?
 *   completeness      — is the response complete or cut off?
 *   hallucinationRisk — does it contain unsupported confident claims?
 *   tone              — neutral default (0.75) without semantic analysis
 *
 * Weights: faithfulness×0.35 + completeness×0.25 + (1−hallucination)×0.30 + tone×0.10
 *
 * Reads from ExecutionRecord:
 *   getResponseText()  — raw adapter output (set via withResponseText() in EXECUTING)
 *   getAdapterId()     — for logging
 * Reads from Intent:
 *   getId()            — for logging
 *   getObjective()     — for faithfulness comparison (confirmed to exist)
 */
@ApplicationScoped
public class OutputQualityScorerService {

    private static final Logger LOG = Logger.getLogger(OutputQualityScorerService.class);

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.quality.scorer.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "app.quality.hallucination.threshold", defaultValue = "0.7")
    double hallucinationThreshold;

    // Regex for suspicious high-confidence claims
    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
            "according to the study|research shows|statistics show|it has been proven|" +
                    "scientists have confirmed|experts agree|the law states|the official policy states|" +
                    "the latest data shows",
            Pattern.CASE_INSENSITIVE
    );

    // Regex for hedging language (reduces hallucination risk)
    private static final Pattern HEDGE_PATTERN = Pattern.compile(
            "i'm not sure|i don't know|i cannot confirm|you should verify|" +
                    "please check|i may be wrong|as of my knowledge|this may have changed",
            Pattern.CASE_INSENSITIVE
    );

    // Specific numbers used confidently (potential hallucination signal)
    private static final Pattern SPECIFIC_NUMBER_PATTERN = Pattern.compile(
            "\\b(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)\\s*(%|percent|million|billion|trillion)"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Score the quality of an execution result.
     * Returns a completed Uni — no blocking, no external calls.
     */
    public Uni<QualityScore> score(Intent intent, ExecutionRecord execution) {
        if (!enabled) {
            return Uni.createFrom().item(QualityScore.skipped("Quality scoring disabled"));
        }

        String response = execution.getResponseText();
        if (response == null || response.isBlank()) {
            LOG.debugf("[Quality] No response text for intent %s — skipping score", intent.getId());
            return Uni.createFrom().item(QualityScore.skipped("No response text available"));
        }

        return Uni.createFrom().item(() -> heuristic(intent, response));
    }

    // ── Heuristic scoring ─────────────────────────────────────────────────────

    private QualityScore heuristic(Intent intent, String response) {
        double faithfulness      = scoreFaithfulness(intent, response);
        double completeness      = scoreCompleteness(response);
        double hallucinationRisk = scoreHallucinationRisk(response);
        double tone              = 0.75; // neutral default

        double overall = (faithfulness      * 0.35)
                + (completeness      * 0.25)
                + ((1.0 - hallucinationRisk) * 0.30)
                + (tone              * 0.10);

        boolean flagged = hallucinationRisk >= hallucinationThreshold;

        LOG.debugf("[Quality] intent=%s overall=%.2f faith=%.2f complete=%.2f hallucination=%.2f flagged=%s",
                intent.getId(), overall, faithfulness, completeness, hallucinationRisk, flagged);

        return new QualityScore(
                clamp(overall),
                clamp(faithfulness),
                clamp(completeness),
                clamp(hallucinationRisk),
                tone,
                flagged,
                "Heuristic scoring — configure LLM judge for higher accuracy",
                "HEURISTIC"
        );
    }

    // ── Dimension scorers ─────────────────────────────────────────────────────

    /**
     * Faithfulness: how many significant words from the objective appear in the response?
     * Uses intent.getObjective() which is confirmed to exist on Intent.
     */
    private double scoreFaithfulness(Intent intent, String response) {
        if (intent.getObjective() == null) return 0.5;

        String objectiveText;
        try {
            objectiveText = mapper.writeValueAsString(intent.getObjective()).toLowerCase();
        } catch (Exception e) {
            objectiveText = intent.getObjective().toString().toLowerCase();
        }

        String responseLower = response.toLowerCase();
        String[] words = objectiveText.replaceAll("[^a-z0-9 ]", " ").split("\\s+");

        int significant = 0;
        int matched     = 0;
        for (String word : words) {
            if (word.length() > 4) {
                significant++;
                if (responseLower.contains(word)) matched++;
            }
        }

        if (significant == 0) return 0.5;
        return Math.min(1.0, (double) matched / significant * 1.2);
    }

    /**
     * Completeness: scored by response length.
     * Very short responses are likely truncated or uninformative.
     */
    private double scoreCompleteness(String response) {
        int len = response.trim().length();
        if (len < 20)   return 0.10;
        if (len < 100)  return 0.50;
        if (len < 500)  return 0.75;
        if (len < 2000) return 0.90;
        return 0.85; // very long — slight penalty for verbosity
    }

    /**
     * Hallucination risk: pattern-based detection.
     * High-confidence unsupported claims push risk up.
     * Hedging language pulls risk down.
     * Suspiciously specific statistics increase risk.
     */
    private double scoreHallucinationRisk(String response) {
        double risk       = 0.0;
        String lowerResp  = response.toLowerCase();

        // High-confidence claims without hedging — each match +0.15
        Matcher highRisk = HIGH_RISK_PATTERN.matcher(lowerResp);
        while (highRisk.find()) {
            risk += 0.15;
        }

        // Hedging language — each match -0.10
        Matcher hedge = HEDGE_PATTERN.matcher(lowerResp);
        while (hedge.find()) {
            risk -= 0.10;
        }

        // Suspiciously specific numbers — more than 3 confident statistics
        Matcher numbers = SPECIFIC_NUMBER_PATTERN.matcher(lowerResp);
        int numberCount = 0;
        while (numbers.find()) numberCount++;
        if (numberCount > 3) risk += 0.20;

        return clamp(risk);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Quality score for a single execution.
     * Stored back onto ExecutionRecord via withQuality().
     */
    public record QualityScore(
            double  overall,
            double  faithfulness,
            double  completeness,
            double  hallucinationRisk,
            double  toneAppropriateness,
            boolean hallucinationDetected,
            String  reasoning,
            String  method
    ) {
        /** True if the overall score meets the given minimum threshold. */
        public boolean isPassing(double threshold) {
            return overall >= threshold;
        }

        /** True if output should be flagged for human review. */
        public boolean requiresHumanReview() {
            return hallucinationDetected || overall < 0.4;
        }

        public static QualityScore skipped(String reason) {
            return new QualityScore(1.0, 1.0, 1.0, 0.0, 1.0, false, reason, "SKIPPED");
        }

        public static QualityScore failed(String reason) {
            return new QualityScore(0.0, 0.0, 0.0, 0.0, 0.0, false, reason, "ERROR");
        }
    }
}


