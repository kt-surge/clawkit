package com.clawkit.cli.intent;

/**
 * Structured intent classification result from the lightweight intent model.
 *
 * <p>The intent model receives only user input + registered server names +
 * active connection target — no local files, git state, or workspace content.
 *
 * <p>PRODUCT-2: user intent layer with work scope isolation.
 */
public record ClassifiedIntent(
    WorkScope scope,
    ServerIntent intent,
    String targetId,
    String service,
    String reply,
    String reason
) {
    /** Empty/NONE sentinel — used when classification fails. */
    public static final ClassifiedIntent NONE = new ClassifiedIntent(
        WorkScope.CLARIFY, ServerIntent.NONE, "", "", "", "classification failed");

    /** Create a CHAT intent with a pre-canned reply. */
    public static ClassifiedIntent chat(String reply, String reason) {
        return new ClassifiedIntent(WorkScope.CHAT, ServerIntent.NONE, "", "", reply, reason);
    }

    /** Create a CLARIFY intent with a question. */
    public static ClassifiedIntent clarify(String question, String reason) {
        return new ClassifiedIntent(WorkScope.CLARIFY, ServerIntent.NONE, "", "", question, reason);
    }

    /** Create a LOCAL_PROJECT intent — delegates to engine with full local tools. */
    public static ClassifiedIntent localProject(String reason) {
        return new ClassifiedIntent(WorkScope.LOCAL_PROJECT, ServerIntent.NONE, "", "", "", reason);
    }
}
