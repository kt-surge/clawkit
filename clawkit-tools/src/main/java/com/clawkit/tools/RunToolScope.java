package com.clawkit.tools;

/**
 * Controls which tools are visible to a single engine run.
 *
 * <p>Enforced at the engine level — tools outside the scope are
 * removed from the tool list before it reaches the model. The model
 * cannot request them, and the executor will reject them if it somehow
 * does.
 *
 * <p>PRODUCT-2: tool scope isolation.
 */
public enum RunToolScope {
    /** All registered tools — local + remote + internal. Default. */
    ALL,
    /** Only local project tools (read, write, bash, grep, glob, git, edit, web_fetch). */
    LOCAL_ONLY,
    /** Only remote read-only tools from the active MCP connection. */
    REMOTE_READ_ONLY,
    /** No tools at all — plain chat. */
    NO_TOOLS
}
