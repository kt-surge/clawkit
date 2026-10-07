package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.DefaultContextPipeline;
import com.clawkit.context.impl.LadderedCompactor;
import java.io.StringWriter;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Evaluation-only original implementation loading; shared public types and all other runtime code stay in the parent. */
final class FrozenContextVariants implements AutoCloseable {
    static final String BASELINE = "benchmarks/baselines/context-memory-context-original-v1";
    static final String MANIFEST_HASH = "0b408bebc99f4175318d5b70e0d0fb56342e581a8ff4e02a4936f9e47f06fbc5";
    private final Path jar;
    private final String jarHash;
    private final OriginalLoader loader;
    private final Class<?> ladder;
    private final Class<?> pipeline;

    private FrozenContextVariants(Path jar) throws Exception {
        this.jar = jar.toAbsolutePath().normalize();
        this.jarHash = EvaluationArtifacts.sha256(Files.readAllBytes(this.jar));
        this.loader = new OriginalLoader(this.jar.toUri().toURL(), ContextPipeline.class.getClassLoader());
        this.ladder = loader.loadClass("com.clawkit.context.impl.LadderedCompactor");
        this.pipeline = loader.loadClass("com.clawkit.context.impl.DefaultContextPipeline");
        verifyOrigin(ladder); verifyOrigin(pipeline);
        if (!ContextPipeline.class.isAssignableFrom(pipeline) || !ContextManager.class.isAssignableFrom(ladder))
            throw new IllegalStateException("original must share the parent's public context types");
    }

    static FrozenContextVariants prepare(EvaluationArtifacts artifacts) throws Exception {
        return prepare(artifacts, artifacts.resolve("source-inputs/" + BASELINE));
    }

    static FrozenContextVariants prepare(EvaluationArtifacts artifacts, Path archive) throws Exception {
        Path manifest = archive.resolve("manifest.json");
        if (!EvaluationArtifacts.sha256(Files.readAllBytes(manifest)).equals(MANIFEST_HASH))
            throw new IllegalStateException("unreviewed original context manifest");
        var data = EvaluationArtifacts.JSON.readTree(manifest.toFile());
        var sources = new ArrayList<Path>();
        var fields = data.path("sourceHashes").fields();
        while (fields.hasNext()) {
            var field = fields.next(); ContinuationSpec.relativeFile(field.getKey());
            if (!field.getKey().startsWith("clawkit-context/src/main/java/") || !field.getKey().endsWith(".java"))
                throw new IllegalStateException("unexpected original compilation input");
            Path source = archive.resolve(field.getKey());
            if (Files.isSymbolicLink(source) || !EvaluationArtifacts.sha256(Files.readAllBytes(source)).equals(field.getValue().asText()))
                throw new IllegalStateException("original source hash differs");
            if (!field.getKey().contains("/impl/") && !Set.of("clawkit-context/src/main/java/com/clawkit/context/Tokenizer.java",
                    "clawkit-context/src/main/java/com/clawkit/context/ContextBudgetAnalyzer.java",
                    "clawkit-context/src/main/java/com/clawkit/context/ConstraintExtractor.java").contains(field.getKey()) && !EvaluationArtifacts.sha256(Files.readAllBytes(
                    EvaluationSourceSnapshot.repository().resolve(field.getKey()))).equals(field.getValue().asText()))
                throw new IllegalStateException("public context contract changed; baseline requires review");
            sources.add(source);
        }
        if (sources.size() != 41) throw new IllegalStateException("all 41 original context sources required");
        sources.sort(Comparator.naturalOrder());
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler unavailable; no baseline substitution permitted");
        Path classes = artifacts.resolve("baseline/classes"); Files.createDirectories(classes);
        var diagnostics = new StringWriter();
        var classpath = new LinkedHashSet<String>();
        for (Class<?> dependency : List.of(ContextPipeline.class, com.clawkit.tools.schema.Message.class,
                com.knuddels.jtokkit.Encodings.class, org.slf4j.Logger.class, com.fasterxml.jackson.databind.JsonNode.class,
                com.fasterxml.jackson.annotation.JsonProperty.class, com.fasterxml.jackson.core.JsonParser.class)) {
            classpath.add(Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        List<String> options = List.of("-encoding", "UTF-8", "--release", "21", "-classpath",
            String.join(java.io.File.pathSeparator, classpath), "-d", classes.toString());
        boolean compiled;
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            compiled = compiler.getTask(diagnostics, manager, null, options, null,
                manager.getJavaFileObjectsFromPaths(sources)).call();
        }
        artifacts.write("baseline/compile.json", Map.of("compiled", compiled, "java", System.getProperty("java.version"),
            "options", options, "diagnostics", diagnostics.toString(), "sourceManifestHash", MANIFEST_HASH));
        if (!compiled) throw new IllegalStateException("original context compilation failed");
        Path jar = artifacts.resolve("baseline/original-context.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar, StandardOpenOption.CREATE_NEW)); var files = Files.walk(classes)) {
            for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                var entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/')); entry.setTime(0);
                output.putNextEntry(entry); Files.copy(file, output); output.closeEntry();
            }
        }
        verifyReviewedPublicAbi(jar);
        var variants = new FrozenContextVariants(jar);
        artifacts.write("baseline/assembly.json", variants.metadata());
        return variants;
    }

    private static void verifyReviewedPublicAbi(Path jar) throws Exception {
        var names=Set.of("com.clawkit.context.Tokenizer","com.clawkit.context.ContextBudgetAnalyzer",
            "com.clawkit.context.ConstraintExtractor");
        try(var loader=new URLClassLoader(new URL[]{jar.toUri().toURL()},ContextPipeline.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
                if(!names.contains(name))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)){var type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;}
            }
        }) {
            for(var name:names)if(!publicAbi(loader.loadClass(name)).equals(publicAbi(Class.forName(name))))throw new IllegalStateException("reviewed public context ABI changed: "+name);
        }
    }
    private static Set<String> publicAbi(Class<?> type) {
        var result=new TreeSet<String>();result.add("interface="+type.isInterface());
        for(var method:type.getMethods())result.add(method.getName()+":"+method.getReturnType().getName()+":"+Arrays.stream(method.getParameterTypes()).map(Class::getName).toList()+":"+method.getModifiers()+":"+method.isDefault());
        for(var constructor:type.getConstructors())result.add("constructor:"+Arrays.stream(constructor.getParameterTypes()).map(Class::getName).toList()+":"+constructor.getModifiers());
        return result;
    }

    ContextPipeline create(ContinuationSpec.Arm arm, Summarizer summarizer, Tokenizer tokenizer,
                           ContextBudgetAnalyzer analyzer, ContextBudgetPolicy budget) throws Exception {
        if (arm != ContinuationSpec.Arm.C2_FROZEN_ORIGINAL_CONTEXT)
            return new DefaultContextPipeline(new LadderedCompactor(summarizer, tokenizer), analyzer, tokenizer, budget);
        if (!EvaluationArtifacts.sha256(Files.readAllBytes(jar)).equals(jarHash)) throw new IllegalStateException("original binary changed");
        ContextManager compactor = (ContextManager) ladder.getConstructor(Summarizer.class, Tokenizer.class).newInstance(summarizer, tokenizer);
        return (ContextPipeline) pipeline.getConstructor(ContextManager.class, ContextBudgetAnalyzer.class, Tokenizer.class,
            ContextBudgetPolicy.class).newInstance(compactor, analyzer, tokenizer, budget);
    }

    Map<String, Object> metadata() throws Exception {
        var shared = new TreeMap<String, String>();
        for (Class<?> type : List.of(com.clawkit.engine.impl.AgentEngine.class, com.clawkit.tools.impl.WriteTool.class,
                com.clawkit.tools.impl.GlobTool.class, com.clawkit.tools.impl.GrepTool.class,
                com.clawkit.engine.impl.VerificationRunLauncher.class, com.clawkit.engine.impl.DefaultMemoryHooks.class, Tokenizer.class, ContextBudgetAnalyzer.class,
                Class.forName("com.clawkit.engine.impl.ReadBatchRepeatTracker"),
                Class.forName("com.clawkit.engine.impl.ReadBatchRepeatTracker$BatchFingerprint"),
                InstanceBudgetContextPipeline.class, InstanceBudgetContextPipeline.InputPlan.class,
                Class.forName("com.clawkit.engine.impl.FileTaskCheckpoint"),
                Class.forName("com.clawkit.engine.impl.FileTaskCheckpoint$ReadRef"),
                Class.forName("com.clawkit.engine.impl.FileTaskCheckpoint$WriteRef"),
                Class.forName("com.clawkit.engine.TaskCompletionCheck"),
                Class.forName("com.clawkit.engine.TaskCompletionCheck$Request"),
                Class.forName("com.clawkit.engine.TaskCompletionCheck$Result"),
                Class.forName("com.clawkit.engine.TaskCompletionCheck$Decision"),
                Class.forName("com.clawkit.engine.impl.TaskCompletionGuard"),
                Class.forName("com.clawkit.engine.impl.TaskCompletionGuard$Outcome"),
                LiveFullPublicAcceptance.class, Class.forName("com.clawkit.evaluation.context.LiveFullPublicAcceptance$Issues"),
                Class.forName("com.clawkit.evaluation.context.LiveFullPublicAcceptance$CheckFailure"))) {
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = type.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("loaded bytecode unavailable");
                shared.put(type.getName(), EvaluationArtifacts.sha256(input.readAllBytes()));
            }
        }
        var candidate = new TreeMap<String, String>();
        for (Class<?> type : List.of(DefaultContextPipeline.class, Class.forName("com.clawkit.context.impl.DefaultContextPipeline$ExchangeTrim"),
                Class.forName("com.clawkit.context.impl.AnchorSnapshotPlanner"), Class.forName("com.clawkit.context.impl.AnchorSnapshotPlanner$Plan"), LadderedCompactor.class,
                com.clawkit.context.impl.MessageMasker.class, Class.forName("com.clawkit.context.impl.ExchangeLayout"),
                Class.forName("com.clawkit.context.impl.TaskSourceProtection"), com.clawkit.context.ConstraintExtractor.class)) {
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (input == null) throw new IllegalStateException("candidate context bytecode unavailable");
                candidate.put(type.getName(), EvaluationArtifacts.sha256(input.readAllBytes()));
            }
        }
        var originalExtractor = loader.loadClass("com.clawkit.context.ConstraintExtractor");
        verifyOrigin(originalExtractor);
        String originalExtractorHash;
        try (var originalJar = new JarFile(jar.toFile());
             var input = originalJar.getInputStream(originalJar.getJarEntry("com/clawkit/context/ConstraintExtractor.class"))) {
            originalExtractorHash = EvaluationArtifacts.sha256(input.readAllBytes());
        }
        return Map.of("originalConstraintExtractorHash", originalExtractorHash,
            "baselineManifestHash", MANIFEST_HASH, "originalJarHash", jarHash,
            "originalPipelineOrigin", pipeline.getProtectionDomain().getCodeSource().getLocation().toString(),
            "originalPrivateImplementationLoader", loader.getClass().getName(), "sharedLoadedBytecodeHashes", shared,
            "candidateContextLoadedBytecodeHashes", candidate, "candidateRevision", "SOURCE_COMPLETED_EXCHANGES_AND_SHARED_STRUCTURED_COUNTING_AND_BOUNDED_USER_REFERENCED_READ_EXCHANGES_AND_FULL_RELATIVE_PATHS_AND_LOW_BUDGET_COMPLETE_EXCHANGE_TRIMMING",
            "scope", "ORIGINAL_CONTEXT_PRIVATE_STRATEGY_AND_PATH_EXTRACTION_SHARED_CORRECTED_COUNTING_AND_OTHER_RUNTIME",
            "sharedRuntimeRevision", "BOUNDED_OUTPUT_RECOVERY_AND_SINGLE_RUN_NATIVE_FILE_NAVIGATION_AND_NATIVE_OVERWRITE_PRECHECK_AND_POST_COMPACTION_SOURCE_VISIBILITY_AND_PUBLIC_TASK_RULE_CONTENT_CLOSING_GUIDANCE_EXPLICIT_SOURCE_VISIBILITY_AND_OPTIONAL_CALLER_TASK_ACCEPTANCE_AND_APPLICATION_PUBLIC_CHECK_AVAILABLE_V2_OPT_IN_AND_NATIVE_PAIRED_READ_ADVISORY_AND_SOURCE_VISIBILITY_SEMANTICS_AND_ROTATING_READ_PHASE_ADVISORY_AND_FULL_TASK_RESERVE_INPUT_PLANNING_AVAILABLE");
    }

    private void verifyOrigin(Class<?> type) throws Exception {
        if (type.getClassLoader() != loader || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).equals(jar))
            throw new IllegalStateException("original class loaded from the wrong artifact");
    }
    @Override public void close() throws java.io.IOException { loader.close(); }

    private static final class OriginalLoader extends URLClassLoader {
        OriginalLoader(URL jar, ClassLoader parent) { super(new URL[] {jar}, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("com.clawkit.context.impl.")
                && !name.equals("com.clawkit.context.ConstraintExtractor")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = findClass(name); // Missing original classes fail; never fall back to the candidate.
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
}
