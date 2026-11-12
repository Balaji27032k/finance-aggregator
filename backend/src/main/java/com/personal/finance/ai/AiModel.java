package com.personal.finance.ai;

import java.util.Optional;

/** A text model. Implementations return nothing when the model cannot be used right now. */
public interface AiModel {

    boolean configured();

    /** The model's reply, or empty when there is no key, the daily limit is reached or the call failed. */
    Optional<String> generate(String system, String user, boolean json);
}
