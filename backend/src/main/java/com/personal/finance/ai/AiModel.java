package com.personal.finance.ai;

import java.util.List;
import java.util.Optional;

/** A text model. Implementations return nothing when the model cannot be used right now. */
public interface AiModel {

    boolean configured();

    /** The model's reply, or empty when there is no key, the daily limit is reached or the call failed. */
    Optional<String> generate(String system, String user, boolean json);

    /** A reply the model wrote after searching the web, with the pages it used. Empty when that is not possible right now. */
    default Optional<Grounded> generateGrounded(String system, String user) {
        return Optional.empty();
    }

    /** A short reason the last call failed, for showing to the user, or null. */
    default String lastProblem() {
        return null;
    }

    record Source(String title, String url) {
    }

    record Grounded(String text, List<Source> sources) {
    }
}
