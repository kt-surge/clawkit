package com.clawkit.cli.ops;

import com.clawkit.ops.loop.automation.AutomationBudgetStatus;
import com.clawkit.ops.loop.automation.AutomationStatus;
import com.clawkit.ops.loop.automation.FixtureAcceleratedSoakRunner;
import com.clawkit.ops.loop.automation.FixtureEvidenceSnapshot;
import com.clawkit.ops.loop.automation.FixtureObserveOnlyLoop;
import com.clawkit.ops.loop.automation.FixtureObservationTimelineReader;
import com.clawkit.ops.loop.automation.ObservedSignal;
import com.clawkit.ops.loop.autonomy.FixtureShadowReplayRunner;
import com.clawkit.ops.loop.autonomy.FixtureShadowEvaluationRunner;
import com.clawkit.ops.loop.autonomy.ShadowDecisionStore;
import com.clawkit.ops.loop.autonomy.ShadowReview;
import com.clawkit.ops.loop.autonomy.ShadowReviewDecision;
import com.clawkit.ops.loop.autonomy.ShadowReviewStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Owns the local OPS-3A Fixture loop for the interactive CLI.
 *
 * <p>There is intentionally no target argument: this controller can only open
 * the fixed {@code fixture-app-down} loop and therefore cannot schedule a
 * registered remote server.
 */
final class FixtureAutomationController implements AutoCloseable {

    record View(
        boolean running,
        ObservedSignal signal,
        AutomationStatus status,
        AutomationBudgetStatus budget,
        int incidentCount
    ) {}

    private final Path stateDirectory;
    private FixtureObserveOnlyLoop loop;

    FixtureAutomationController(Path stateDirectory) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory")
            .toAbsolutePath().normalize();
    }

    synchronized void start() throws IOException {
        if (loop != null) return;
        loop = FixtureObserveOnlyLoop.open(FixtureObserveOnlyLoop.Config.defaults(stateDirectory));
        loop.start();
    }

    synchronized void pause() throws IOException {
        requireRunning().pause();
    }

    synchronized void resume() throws IOException {
        requireRunning().resume();
    }

    synchronized void setSignal(ObservedSignal signal) {
        requireRunning().setSignal(signal);
    }

    synchronized View view() {
        if (loop == null) return new View(false, null, null, null, 0);
        return new View(true, loop.signal(), loop.status(), loop.budgetStatus(), loop.incidents().size());
    }

    synchronized void stop() {
        if (loop == null) return;
        loop.close();
        loop = null;
    }

    /** Reads verified Fixture replay entries even after the scheduler has stopped. */
    synchronized List<FixtureObservationTimelineReader.Entry> timeline(int limit) throws IOException {
        return FixtureObservationTimelineReader.read(
            stateDirectory.resolve("observation-timeline.jsonl"), limit);
    }

    /** Creates a byte-bound evidence bundle only after the scheduler has drained and stopped. */
    synchronized FixtureEvidenceSnapshot.Result snapshot() throws IOException {
        if (loop != null) {
            throw new IllegalStateException(
                "Fixture observe-only loop is running; use /ops observe fixture stop before snapshot");
        }
        return FixtureEvidenceSnapshot.create(stateDirectory, Clock.systemUTC());
    }

    /** Runs an isolated 72-logical-hour scenario; never reuses the live Fixture state. */
    synchronized FixtureAcceleratedSoakRunner.Result acceleratedSoak() throws IOException {
        if (loop != null) {
            throw new IllegalStateException(
                "Fixture observe-only loop is running; use /ops observe fixture stop before accelerated soak");
        }
        return FixtureAcceleratedSoakRunner.run(stateDirectory.resolve("soak-evidence"));
    }

    /** Replays the newest immutable Fixture snapshot into an A3 local-only record. */
    synchronized FixtureShadowReplayRunner.Result shadowReplay() throws IOException {
        if (loop != null) {
            throw new IllegalStateException(
                "Fixture observe-only loop is running; use /ops observe fixture stop before Shadow replay");
        }
        Path snapshots = stateDirectory.resolve("snapshots");
        if (!Files.isDirectory(snapshots)) {
            throw new IllegalStateException(
                "No Fixture snapshot exists; use /ops observe fixture snapshot before Shadow replay");
        }
        Path newest;
        try (var entries = Files.list(snapshots)) {
            newest = entries.filter(Files::isDirectory)
                .max(Comparator.comparing(path -> {
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (IOException e) {
                        throw new SnapshotListingFailure(e);
                    }
                }))
                .orElseThrow(() -> new IllegalStateException(
                    "No Fixture snapshot exists; use /ops observe fixture snapshot before Shadow replay"));
        } catch (SnapshotListingFailure e) {
            throw e.cause;
        }
        return FixtureShadowReplayRunner.run(newest, stateDirectory.resolve("shadow-evidence"), Clock.systemUTC());
    }

    /** Runs the fixed A3 Fixture contract matrix; it cannot open a remote target. */
    synchronized FixtureShadowEvaluationRunner.Result shadowEvaluation() throws IOException {
        if (loop != null) {
            throw new IllegalStateException(
                "Fixture observe-only loop is running; use /ops observe fixture stop before Shadow evaluation");
        }
        return FixtureShadowEvaluationRunner.run(stateDirectory.resolve("shadow-evaluation"), Clock.systemUTC());
    }

    /** Records a human counterfactual choice; it never creates an ApprovalGrant or FixSession. */
    synchronized ShadowReviewStore.RecordedReview reviewLatestShadow(ShadowReviewDecision choice) throws IOException {
        if (loop != null) {
            throw new IllegalStateException(
                "Fixture observe-only loop is running; use /ops observe fixture stop before Shadow review");
        }
        Path root = stateDirectory.resolve("shadow-evidence");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("No Fixture Shadow evidence exists; run /ops observe fixture shadow first");
        }
        Path latest;
        try (var entries = Files.list(root)) {
            latest = entries.filter(Files::isDirectory)
                .max(Comparator.comparing(path -> {
                    try {
                        return Files.getLastModifiedTime(path);
                    } catch (IOException e) {
                        throw new SnapshotListingFailure(e);
                    }
                }))
                .orElseThrow(() -> new IllegalStateException(
                    "No Fixture Shadow evidence exists; run /ops observe fixture shadow first"));
        } catch (SnapshotListingFailure e) {
            throw e.cause;
        }
        var decisions = new ShadowDecisionStore(latest.resolve("decisions")).list();
        if (decisions.size() != 1) {
            throw new IllegalStateException("Fixture Shadow review requires exactly one persisted decision");
        }
        ShadowReview review = ShadowReview.from(decisions.getFirst(), Objects.requireNonNull(choice, "choice"),
            Clock.systemUTC().instant());
        return new ShadowReviewStore(latest.resolve("reviews")).record(review);
    }

    private FixtureObserveOnlyLoop requireRunning() {
        if (loop == null) {
            throw new IllegalStateException("Fixture observe-only loop is not running; use /ops observe fixture start");
        }
        return loop;
    }

    @Override
    public void close() {
        stop();
    }

    private static final class SnapshotListingFailure extends RuntimeException {
        private final IOException cause;

        private SnapshotListingFailure(IOException cause) {
            this.cause = cause;
        }
    }
}
