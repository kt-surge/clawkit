package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.automation.FixtureAcceleratedSoakRunner;
import com.clawkit.ops.loop.automation.FixtureEvidenceSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Non-interactive reproducibility entrypoint for the Fixture-only autonomy
 * evidence bundle. It deliberately composes no remote session or repair API.
 */
public final class FixtureAutonomyEvidenceMain {
    private FixtureAutonomyEvidenceMain() { }

    public record Result(
        FixtureAcceleratedSoakRunner.Result observe,
        FixtureShadowReplayRunner.Result shadow,
        FixtureEvidenceSnapshot.Result freshSnapshot,
        FixtureShadowReplayRunner.Result freshShadow,
        FixtureShadowEvaluationRunner.Result evaluation
    ) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: FixtureAutonomyEvidenceMain <output-directory>");
        }
        Result result = run(Path.of(args[0]));
        System.out.println("A0 snapshot: " + result.observe().snapshot().directory());
        System.out.println("A0 logical hours: " + result.observe().report().logicalHours());
        System.out.println("A3 shadow: " + result.shadow().directory());
        System.out.println("A3 decision: " + result.shadow().recordedDecision().decision().decisionId()
            + " sideEffects=" + result.shadow().recordedDecision().decision().sideEffectCalls());
        System.out.println("A0 fresh snapshot: " + result.freshSnapshot().directory());
        System.out.println("A3 fresh decision: " + result.freshShadow().recordedDecision().decision().decisionId()
            + " outcome=" + result.freshShadow().recordedDecision().decision().outcome()
            + " sideEffects=" + result.freshShadow().recordedDecision().decision().sideEffectCalls());
        System.out.println("A3 evaluation: " + result.evaluation().directory()
            + " total=" + result.evaluation().report().counts().total()
            + " sideEffects=" + result.evaluation().report().sideEffectCalls());
    }

    public static Result run(Path outputDirectory) throws IOException {
        Path root = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        Files.createDirectories(root);
        FixtureAcceleratedSoakRunner.Result observe = FixtureAcceleratedSoakRunner.run(root.resolve("a0-soak"));
        // The final A0 observation is one logical hour before this snapshot.
        // Keeping snapshot time makes the A3 replay correctly demonstrate that
        // stale evidence remains ASK_REQUIRED instead of being upgraded.
        Clock fixtureClock = Clock.fixed(observe.snapshot().createdAt(), ZoneOffset.UTC);
        FixtureShadowReplayRunner.Result shadow = FixtureShadowReplayRunner.run(observe.snapshot().directory(),
            root.resolve("a3-shadow"), fixtureClock);
        FixtureEvidenceSnapshot.Result freshSnapshot =
            FixtureAcceleratedSoakRunner.runFreshAppDown(root.resolve("a0-fresh"));
        FixtureShadowReplayRunner.Result freshShadow = FixtureShadowReplayRunner.run(
            freshSnapshot.directory(), root.resolve("a3-fresh-shadow"),
            Clock.fixed(freshSnapshot.createdAt(), ZoneOffset.UTC));
        FixtureShadowEvaluationRunner.Result evaluation = FixtureShadowEvaluationRunner.run(
            root.resolve("a3-evaluation"), fixtureClock);
        return new Result(observe, shadow, freshSnapshot, freshShadow, evaluation);
    }
}
