package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.ApprovalDecision;
import com.clawkit.ops.delivery.UserIncidentStatus;
import com.clawkit.ops.loop.autonomy.ShadowReview;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

/**
 * 脱敏本地使用日志（PRODUCT-3 阶段 5 dogfood）。
 *
 * <p>写入 {@code ~/.clawkit/dogfood/usage.jsonl}，每行一条 JSON。
 * 不记录 IP、密钥、SSH 参数或完整诊断文本。
 *
 * <p>线程安全：调用方保证串行（REPL 单线程）。
 */
public final class DogfoodLogger {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logFile;
    private final Clock clock;
    private Instant approvalShownAt;
    private String targetId;
    private String taskType;
    private String environment;
    private int userActions;
    private ApprovalDecision decision;
    private Long readingTimeSeconds;

    public DogfoodLogger(Path clawkitHome) {
        this(clawkitHome, Clock.systemDefaultZone());
    }

    DogfoodLogger(Path clawkitHome, Clock clock) {
        this.logFile = clawkitHome.resolve("dogfood").resolve("usage.jsonl");
        this.clock = clock;
    }

    /** 记录一次用户提交的调查任务。 */
    public void beginTask(String targetId, String taskType, String environment) {
        this.targetId = targetId;
        this.taskType = taskType;
        this.environment = environment;
        this.userActions = 1;
        this.approvalShownAt = null;
        this.decision = null;
        this.readingTimeSeconds = null;
    }

    /** 审批提示展示时调用，记录起始时间。 */
    public void markApprovalShown() {
        approvalShownAt = clock.instant();
    }

    /** 用户在审批框中做出决定时调用，阅读时长在这里结算。 */
    public void recordDecision(ApprovalDecision decision) {
        this.decision = decision;
        this.userActions++;
        if (approvalShownAt != null) {
            this.readingTimeSeconds = Math.max(0,
                Duration.between(approvalShownAt, clock.instant()).getSeconds());
        }
    }

    /**
     * 调查/修复完成时调用。如果 approvalShownAt 已设置，
     * 自动计算阅读决策时间并重置。
     */
    public void log(String targetId, String taskType, ApprovalDecision decision,
                    UserIncidentStatus finalStatus, String incidentId) {
        ObjectNode entry = MAPPER.createObjectNode();
        Instant now = clock.instant();
        entry.put("date", DATE_FMT.format(now));
        entry.put("environment", environment != null ? environment : "UNKNOWN");
        entry.put("target", this.targetId != null ? this.targetId : targetId != null ? targetId : "");
        entry.put("taskType", this.taskType != null ? this.taskType : taskType);
        entry.put("userActions", userActions);
        entry.put("incidentId", incidentId != null ? incidentId : "");

        ApprovalDecision effectiveDecision = this.decision != null ? this.decision : decision;
        if (readingTimeSeconds != null) {
            entry.put("readingTimeSeconds", readingTimeSeconds);
        } else {
            entry.putNull("readingTimeSeconds");
        }

        entry.put("decision", effectiveDecision != null ? effectiveDecision.name() : "NONE");
        entry.put("finalStatus", finalStatus != null ? finalStatus.name() : "UNKNOWN");
        entry.putNull("understood");
        entry.putNull("fellBackToSsh");
        entry.putNull("frictionDescription");
        entry.putNull("frictionPriority");

        append(entry);
        clearTask();
    }

    /**
     * 追加一条自填摩擦记录（用户手动编辑日志或通过 /ops feedback 命令）。
     */
    public void logFriction(String incidentId, String description, String priority) {
        logFeedback(incidentId, null, null, description, priority);
    }

    /** 追加用户反馈；不修改原任务记录，以 incidentId 关联。 */
    public void logFeedback(String incidentId, Boolean understood, Boolean fellBackToSsh,
                            String description, String priority) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("date", DATE_FMT.format(clock.instant()));
        entry.put("incidentId", incidentId != null ? incidentId : "");
        entry.put("userActions", 1);
        if (understood == null) entry.putNull("understood"); else entry.put("understood", understood);
        if (fellBackToSsh == null) entry.putNull("fellBackToSsh"); else entry.put("fellBackToSsh", fellBackToSsh);
        entry.put("frictionDescription", sanitizeFeedback(description));
        entry.put("frictionPriority", priority != null ? priority : "");
        entry.put("type", "FRICTION");
        append(entry);
    }

    /**
     * 追加一条 A3 人工反事实选择。它是 Fixture 证据，不是 ApprovalGrant、修复或验证结果。
     * 只保存可关联的不可变标识和 hash，不写入目标连接信息或诊断正文。
     */
    public void logShadowReview(ShadowReview review) {
        if (review == null) return;
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("date", DATE_FMT.format(clock.instant()));
        entry.put("type", "SHADOW_REVIEW");
        entry.put("environment", "FIXTURE");
        entry.put("reviewId", review.reviewId());
        entry.put("decisionId", review.decisionId());
        entry.put("policyHash", review.policyHash());
        entry.put("evidenceSnapshotHash", review.evidenceSnapshotHash());
        entry.put("reviewerDecision", review.reviewerDecision().name());
        entry.put("sideEffectCalls", review.sideEffectCalls());
        entry.put("userActions", 1);
        append(entry);
    }

    /**
     * Reads only aggregate health signals from the local dogfood log. It never
     * returns targets, incident ids, feedback text, or other task content.
     */
    public Summary summary() throws IOException {
        if (!Files.isRegularFile(logFile)) return Summary.missing();
        if (Files.size(logFile) > 5 * 1024 * 1024) {
            throw new IOException("dogfood log exceeds the 5 MiB local summary limit");
        }
        int valid = 0, invalid = 0, tasks = 0, friction = 0, reviews = 0;
        int wouldApprove = 0, wouldReject = 0, needsEvidence = 0;
        java.util.SortedSet<LocalDate> days = new java.util.TreeSet<>();
        Set<String> reviewIds = new HashSet<>();
        for (String line : Files.readAllLines(logFile)) {
            if (line.isBlank()) continue;
            try {
                var entry = MAPPER.readTree(line);
                LocalDate date = LocalDate.parse(entry.path("date").asText());
                String type = entry.path("type").asText("TASK");
                if ("SHADOW_REVIEW".equals(type)) {
                    if (!"FIXTURE".equals(entry.path("environment").asText())
                        || !entry.path("reviewId").asText().matches("shadow-review-[0-9a-f]{32}")
                        || !reviewIds.add(entry.path("reviewId").asText())
                        || !entry.path("decisionId").asText().matches("shadow-[0-9a-f]{32}")
                        || !entry.path("policyHash").asText().matches("[0-9a-f]{64}")
                        || !entry.path("evidenceSnapshotHash").asText().matches("[0-9a-f]{64}")
                        || entry.path("sideEffectCalls").asInt(-1) != 0) throw new IllegalArgumentException();
                    switch (entry.path("reviewerDecision").asText()) {
                        case "WOULD_APPROVE" -> wouldApprove++;
                        case "WOULD_REJECT" -> wouldReject++;
                        case "NEEDS_MORE_EVIDENCE" -> needsEvidence++;
                        default -> throw new IllegalArgumentException();
                    }
                    reviews++;
                } else if ("FRICTION".equals(type)) {
                    friction++;
                } else if ("TASK".equals(type)) {
                    if (!isValidTask(entry)) throw new IllegalArgumentException();
                    tasks++;
                } else {
                    throw new IllegalArgumentException();
                }
                days.add(date);
                valid++;
            } catch (Exception ignored) {
                invalid++;
            }
        }
        boolean consecutive = !days.isEmpty()
            && ChronoUnit.DAYS.between(days.first(), days.last()) + 1 == days.size();
        return new Summary(true, valid, invalid, days.size(), consecutive, tasks, friction, reviews,
            wouldApprove, wouldReject, needsEvidence);
    }

    /**
     * A legacy task entry has no {@code type} field, so the summary must not
     * mistake an arbitrary JSON line with only a date for real dogfood.  Keep
     * this check limited to aggregate-safe fields: no task text is parsed or
     * surfaced by {@link #summary()}.
     */
    private static boolean isValidTask(com.fasterxml.jackson.databind.JsonNode entry) {
        String environment = entry.path("environment").asText();
        if (!("REMOTE_READONLY".equals(environment) || "FIXTURE".equals(environment))) return false;
        if (entry.path("target").asText().isBlank() || entry.path("taskType").asText().isBlank()
            || entry.path("incidentId").asText().isBlank() || entry.path("userActions").asInt(-1) < 1) {
            return false;
        }
        String decision = entry.path("decision").asText();
        if (!("NONE".equals(decision) || isEnumValue(ApprovalDecision.class, decision))) return false;
        String finalStatus = entry.path("finalStatus").asText();
        if (!("UNKNOWN".equals(finalStatus) || isEnumValue(UserIncidentStatus.class, finalStatus))) return false;
        var readingTime = entry.path("readingTimeSeconds");
        return readingTime.isNull() || (readingTime.isIntegralNumber() && readingTime.asLong() >= 0);
    }

    private static <E extends Enum<E>> boolean isEnumValue(Class<E> type, String value) {
        try {
            Enum.valueOf(type, value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Aggregate-only status used by the CLI; it is not an A4 eligibility verdict. */
    public record Summary(boolean logPresent, int validEvents, int invalidEvents, int recordedDays,
                          boolean consecutiveDays, int taskEvents, int frictionEvents, int shadowReviews,
                          int wouldApprove, int wouldReject, int needsMoreEvidence) {
        static Summary missing() {
            return new Summary(false, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);
        }
        public boolean hasSevenConsecutiveDays() { return consecutiveDays && recordedDays >= 7; }
    }

    private static String sanitizeFeedback(String description) {
        if (description == null) return "";
        String compact = description.replaceAll("[\\r\\n]+", " ").strip();
        compact = compact.replaceAll("(?i)(sk-[a-z0-9_-]+|bearer\\s+\\S+|authorization\\s*[:=]\\s*\\S+|password\\s*[:=]\\s*\\S+)", "[已脱敏]");
        return compact.length() > 300 ? compact.substring(0, 300) : compact;
    }

    private void clearTask() {
        approvalShownAt = null;
        targetId = null;
        taskType = null;
        environment = null;
        userActions = 0;
        decision = null;
        readingTimeSeconds = null;
    }

    private void append(ObjectNode entry) {
        try {
            Files.createDirectories(logFile.getParent());
            String line = MAPPER.writeValueAsString(entry) + "\n";
            Files.writeString(logFile, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[dogfood] write failed: " + e.getMessage());
        }
    }
}
