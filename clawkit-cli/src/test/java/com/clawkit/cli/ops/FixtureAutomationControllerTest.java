package com.clawkit.cli.ops;

import com.clawkit.ops.loop.automation.ObservedSignal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixtureAutomationControllerTest {

    @TempDir
    Path tempDir;

    private FixtureAutomationController controller;

    @AfterEach
    void closeController() {
        if (controller != null) controller.close();
    }

    @Test
    void startsOnlyTheFixedFixtureLoopAndStopsIt() throws Exception {
        controller = new FixtureAutomationController(tempDir);

        assertThat(controller.view().running()).isFalse();
        controller.start();
        controller.setSignal(ObservedSignal.HEALTHY);

        var view = controller.view();
        assertThat(view.running()).isTrue();
        assertThat(view.signal()).isEqualTo(ObservedSignal.HEALTHY);
        assertThat(view.budget().providerLimit()).isZero();
        assertThat(tempDir.resolve("automation-state.json")).exists();
        assertThat(tempDir.resolve("incident-registry.jsonl")).exists();

        controller.stop();
        assertThat(controller.view().running()).isFalse();
    }

    @RepeatedTest(10)
    void controllerStartsTheFirstFixtureObservationOnTheJdkScheduler() throws Exception {
        controller = new FixtureAutomationController(tempDir);
        controller.start();

        awaitFirstCompletedObservation();

        var view = controller.view();
        assertThat(view.status().requested()).isGreaterThanOrEqualTo(1);
        assertThat(view.status().completed() + view.status().merged()).isGreaterThanOrEqualTo(1);
        assertThat(view.budget().providerConsumed()).isZero();
        assertThat(view.incidentCount()).isEqualTo(1);

        // Completion is persisted before the timeline projection. Stop drains
        // the admitted callback before we verify its immutable output.
        controller.stop();
        assertThat(controller.timeline(10)).singleElement().satisfies(entry -> {
            assertThat(entry.type()).isEqualTo("OBSERVATION_INCIDENT_CREATED");
            assertThat(entry.providerCalled()).isFalse();
            assertThat(entry.evidenceRefs()).allMatch(ref -> ref.startsWith("fixture://"));
        });

        assertThat(controller.timeline(10)).hasSize(1);
    }

    @Test
    void snapshotRequiresTheLoopToBeStoppedAndProducesAVerifiedBundle() throws Exception {
        controller = new FixtureAutomationController(tempDir);
        controller.start();

        awaitFirstCompletedObservation();
        assertThatThrownBy(controller::snapshot)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stop");

        controller.stop();
        var snapshot = controller.snapshot();

        assertThat(snapshot.directory()).isDirectory();
        assertThat(snapshot.targetId()).isEqualTo("fixture-app-down");
        assertThat(snapshot.timelineEvents()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.files()).hasSize(3);
    }

    @Test
    void shadowReplayRequiresAStoppedImmutableSnapshotAndNeverExposesAnExecutor() throws Exception {
        controller = new FixtureAutomationController(tempDir);
        controller.start();
        awaitFirstCompletedObservation();
        assertThatThrownBy(controller::shadowReplay).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("stop");

        controller.stop();
        controller.snapshot();
        var result = controller.shadowReplay();

        assertThat(result.recordedDecision().decision().sideEffectCalls()).isZero();
        assertThat(result.directory()).startsWith(tempDir.resolve("shadow-evidence"));
        assertThat(result.recordedDecision().decision().candidateAction()).isEqualTo("restart_service");
    }

    @Test
    void shadowEvaluationIsFixtureOnlyAndReportsZeroSideEffects() throws Exception {
        controller = new FixtureAutomationController(tempDir);
        var result = controller.shadowEvaluation();

        assertThat(result.report().counts().total()).isEqualTo(100);
        assertThat(result.report().sideEffectCalls()).isZero();
        assertThat(result.report().passed()).isTrue();
        assertThat(result.directory()).startsWith(tempDir.resolve("shadow-evaluation"));
    }

    @Test
    void shadowReviewRecordsOnlyAHumanCounterfactualChoice() throws Exception {
        controller = new FixtureAutomationController(tempDir);
        controller.start();
        awaitFirstCompletedObservation();
        controller.stop();
        controller.snapshot();
        controller.shadowReplay();

        var review = controller.reviewLatestShadow(com.clawkit.ops.loop.autonomy.ShadowReviewDecision.NEEDS_MORE_EVIDENCE);

        assertThat(review.created()).isTrue();
        assertThat(review.review().sideEffectCalls()).isZero();
        assertThat(review.review().reviewerDecision().name()).isEqualTo("NEEDS_MORE_EVIDENCE");
    }

    @Test
    void acceleratedSoakUsesIsolatedFixtureStateAndProducesAReport() throws Exception {
        controller = new FixtureAutomationController(tempDir);

        var result = controller.acceleratedSoak();

        assertThat(result.report().passed()).isTrue();
        assertThat(result.report().mode()).isEqualTo("ACCELERATED_LOGICAL_TIME");
        assertThat(result.report().counts().failed()).isZero();
        assertThat(result.report().providerConsumed()).isZero();
        assertThat(result.reportPath()).isRegularFile();
        assertThat(result.runDirectory()).startsWith(tempDir.resolve("soak-evidence"));
    }

    @Test
    void cannotControlANonRunningLoop() {
        controller = new FixtureAutomationController(tempDir);
        assertThatThrownBy(() -> controller.setSignal(ObservedSignal.APP_DOWN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not running");
    }

    @Test
    void opsFixtureCommandDoesNotRequireRemoteOrRepairDependencies() {
        controller = new FixtureAutomationController(tempDir);
        OpsCommandHandler handler = new OpsCommandHandler(null, null, null, controller);
        try {
            assertThat(handler.handle("observe fixture start")).isTrue();
            assertThat(controller.view().running()).isTrue();
            assertThat(handler.handle("observe fixture unknown")).isTrue();
            assertThat(controller.view().signal()).isEqualTo(ObservedSignal.UNKNOWN);
            assertThat(controller.view().budget().providerLimit()).isZero();
        } finally {
            handler.close();
        }
    }

    @Test
    void opsFixtureShadowReviewCommandRecordsOnlyTheCounterfactualChoice() throws Exception {
        controller = new FixtureAutomationController(tempDir);
        controller.start();
        awaitFirstCompletedObservation();
        controller.stop();
        controller.snapshot();
        controller.shadowReplay();

        JLineInvestigationInteraction interaction = new JLineInvestigationInteraction(null);
        DogfoodLogger logger = new DogfoodLogger(tempDir.resolve("dogfood-home"));
        interaction.setDogfoodLogger(logger);
        OpsCommandHandler handler = new OpsCommandHandler(null, null, interaction, controller);
        try {
            assertThat(handler.handle("observe fixture shadow-review reject")).isTrue();
            assertThat(handler.handle("observe fixture shadow-review reject")).isTrue();
            var replay = controller.reviewLatestShadow(
                com.clawkit.ops.loop.autonomy.ShadowReviewDecision.WOULD_REJECT);
            assertThat(replay.created()).isFalse();
            assertThat(replay.review().sideEffectCalls()).isZero();
            assertThat(logger.summary().shadowReviews()).isEqualTo(1);
            assertThat(logger.summary().wouldReject()).isEqualTo(1);
        } finally {
            handler.close();
        }
    }

    private void awaitFirstCompletedObservation() throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var status = controller.view().status();
        while (status.completed() + status.merged() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
            status = controller.view().status();
        }
        assertThat(status.completed() + status.merged())
            .as("first JDK-scheduled observation must complete within five seconds; status=%s", status)
            .isGreaterThanOrEqualTo(1);
    }
}
