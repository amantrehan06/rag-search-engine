package com.productsearch.evaluation;

public record ProductEvaluationResult(
        double relevance,        // do the returned products match the user's query?
        double budgetAdherence,  // are all prices within the stated budget?
        double intentAccuracy,   // did the parser correctly extract the intent?
        String summary           // one-sentence overall judgement
) {}
