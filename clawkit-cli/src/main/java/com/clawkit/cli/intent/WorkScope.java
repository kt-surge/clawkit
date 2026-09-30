package com.clawkit.cli.intent;

/**
 * First-level classification: which domain does the user's request belong to?
 *
 * <p>Determines tool availability and execution path.
 *
 * <p>PRODUCT-2: work scope isolation.
 */
public enum WorkScope {
    /** User wants to interact with registered remote servers. */
    REMOTE_SERVER,
    /** User wants to work on the local project code. */
    LOCAL_PROJECT,
    /** Simple chat — no tools needed. */
    CHAT,
    /** Unclear — need to ask for clarification. */
    CLARIFY
}
