package com.clawkit.cli.intent;

/**
 * Second-level classification: what specific server action does the user want?
 *
 * <p>Only meaningful when {@link WorkScope} is {@code REMOTE_SERVER}.
 *
 * <p>PRODUCT-2: user intent layer.
 */
public enum ServerIntent {
    /** Show registered servers, connection status, next steps. */
    SERVER_OVERVIEW,
    /** Show current connection status only. */
    CONNECTION_STATUS,
    /** Explain the mounted remote tools and their read-only boundaries. */
    TOOL_EXPLANATION,
    /** General question in server mode, scoped to the active target. */
    SERVER_QUERY,
    /** Connect to a registered server. */
    CONNECT_TARGET,
    /** Run a read-only remote health check. */
    QUICK_CHECK,
    /** Enter full investigation workflow. */
    INVESTIGATE_SERVICE,
    /** No specific server action (used for non-REMOTE_SERVER scopes). */
    NONE
}
