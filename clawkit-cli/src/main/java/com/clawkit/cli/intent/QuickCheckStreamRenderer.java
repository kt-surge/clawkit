package com.clawkit.cli.intent;

import java.io.PrintStream;

/** Streams only the final Quick Check report and suppresses model preamble. */
final class QuickCheckStreamRenderer {

    static final String REPORT_MARKER = "## 检查结果";
    private static final int MAX_PREAMBLE_BUFFER = 8_192;

    private final PrintStream out;
    private final StringBuilder pending = new StringBuilder();
    private boolean started;
    private boolean wroteAny;
    private boolean endedWithNewline;

    QuickCheckStreamRenderer(PrintStream out) {
        this.out = out;
    }

    void accept(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (started) {
            write(delta);
            return;
        }

        pending.append(delta);
        int markerIndex = pending.indexOf(REPORT_MARKER);
        if (markerIndex >= 0) {
            started = true;
            String reportStart = pending.substring(markerIndex);
            pending.setLength(0);
            write(reportStart);
            return;
        }

        if (pending.length() > MAX_PREAMBLE_BUFFER) {
            int suffixLength = Math.min(REPORT_MARKER.length() - 1, pending.length());
            String suffix = pending.substring(pending.length() - suffixLength);
            pending.setLength(0);
            pending.append(suffix);
        }
    }

    boolean started() {
        return started;
    }

    void finish() {
        if (wroteAny && !endedWithNewline) out.println();
        out.flush();
    }

    static String cleanFinalResult(String result) {
        if (result == null) return null;
        int markerIndex = result.indexOf(REPORT_MARKER);
        return markerIndex >= 0 ? result.substring(markerIndex).strip() : result.strip();
    }

    private void write(String text) {
        if (!wroteAny) out.println();
        out.print(text);
        out.flush();
        wroteAny = true;
        endedWithNewline = text.endsWith("\n") || text.endsWith("\r");
    }
}
