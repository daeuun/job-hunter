package jobhunter

import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private val logTime = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(KST)

fun log(msg: String) = println("[${logTime.format(Instant.now())}] $msg")

/**
 * 실행 옵션
 *   --config <path>   설정 파일 (기본 config.json)
 *   --dry-run         수집·필터·상세 크롤링까지만 하고 LLM 평가는 건너뜀 (상태도 저장하지 않음)
 *   --no-detail       상세 크롤링 없이 API 메타데이터만으로 평가
 *   --since-hours N   이번 실행만 N시간 전 공고부터 다시 조회
 */
fun main(args: Array<String>) {
    val opts = parseArgs(args)
    val config = AppConfig.load(Paths.get(opts["config"] ?: "config.json"))
    val dryRun = "dry-run" in opts
    val withDetail = "no-detail" !in opts

    val accessKey = System.getenv(config.saramin.accessKeyEnv)
        ?: fail("환경변수 ${config.saramin.accessKeyEnv}에 사람인 access-key를 넣어주세요.")

    val dataDir = Paths.get(config.output.dataDir)
    val workRoot = dataDir.resolve("work")
    val store = Store(dataDir)
    val stats = RunStats()
    val runStartedAt = Instant.now()

    // 1. 수집 기준 시각: 마지막 실행 1시간 전부터 (경계 누락 방지, 중복은 seen으로 제거)
    val since = opts["since-hours"]?.toLongOrNull()?.let { runStartedAt.minus(Duration.ofHours(it)) }
        ?: store.lastRunAt()?.minus(Duration.ofHours(1))
        ?: runStartedAt.minus(Duration.ofHours(config.saramin.initialLookbackHours))
    log("사람인 공고 수집 시작 (since=${DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST).format(since)})")

    // 테스트용 엔드포인트 오버라이드 (평소에는 설정하지 않음)
    val apiBase = System.getenv("JOBHUNTER_SARAMIN_API_URL") ?: "https://oapi.saramin.co.kr/job-search"
    val siteBase = System.getenv("JOBHUNTER_SARAMIN_SITE_URL") ?: "https://www.saramin.co.kr"

    val saramin = SaraminClient(config.saramin, accessKey, baseUrl = apiBase)
    val jobs = try {
        saramin.fetchSince(since)
    } catch (e: SaraminApiException) {
        fail(e.message ?: "사람인 API 오류")
    }
    stats.fetched = jobs.size
    stats.apiCalls = saramin.callCount

    // 2. 기처리 제외 + 하드 필터
    val filter = HardFilter(config.filter)
    val candidates = mutableListOf<JobPosting>()
    for (job in jobs) {
        if (store.isSeen(job.id)) { stats.alreadySeen++; continue }
        val reason = filter.reject(job)
        if (reason != null) {
            stats.filtered++
            if (!dryRun) store.markSeen(job.id, "filtered: $reason")
        } else {
            candidates += job
        }
    }
    log("후보 ${candidates.size}건 (수집 ${stats.fetched} / 기처리 ${stats.alreadySeen} / 필터 제외 ${stats.filtered})")

    // 3. 처리량 상한: 넘치는 공고는 seen에 넣지 않고 다음 실행으로 넘긴다
    val batch = candidates.take(config.crawler.maxDetailsPerRun)
    stats.deferred = candidates.size - batch.size

    val crawler = DetailCrawler(config.crawler, siteBase = siteBase)
    val claude = ClaudeCli(config.claude)
    val scorer = if (dryRun || batch.isEmpty()) null else Scorer(
        claude = claude,
        profile = ProfileBuilder(
            claude = claude,
            resumePath = Paths.get(config.profile.resumePath),
            cachePath = dataDir.resolve("profile.json"),
            workRoot = workRoot,
        ).load(),
        preferences = config.profile.preferences,
    )

    val scored = mutableListOf<ScoredJob>()
    var consecutiveFailures = 0
    for ((i, job) in batch.withIndex()) {
        if (consecutiveFailures >= 3) {
            // 인증 만료·사용량 한도 등 공통 원인일 가능성이 높으니 남은 공고는 다음 실행으로 넘긴다
            log("Claude 평가가 연속 3회 실패해 중단합니다. 남은 ${batch.size - i}건은 다음 실행에서 처리합니다.")
            stats.deferred += batch.size - i
            break
        }
        log("[${i + 1}/${batch.size}] ${job.company} — ${job.title}")
        val jobWork = workRoot.resolve(job.id)
        try {
            val detail = if (withDetail) {
                runCatching { crawler.fetch(job, jobWork) }
                    .onFailure { log("  상세 수집 실패: ${it.message}") }
                    .getOrNull()
            } else null
            if (detail != null) log("  본문 ${detail.text.length}자, 이미지 ${detail.imagePaths.size}개")

            if (scorer == null) continue
            val s = scorer.score(job, detail, jobWork)
            log("  → ${s.score}점 ${s.verdict}")
            scored += s
            stats.scored++
            consecutiveFailures = 0
            store.markSeen(job.id, "scored", s.score)
        } catch (e: Exception) {
            stats.errors++
            consecutiveFailures++
            log("  평가 실패(다음 실행에 재시도): ${e.message}")
        } finally {
            jobWork.toFile().deleteRecursively()
        }
    }

    if (dryRun) {
        log("dry-run 종료. ${stats.summaryLine()}")
        return
    }

    if (scored.isNotEmpty()) {
        val reportPath = Report.write(dataDir, scored, stats)
        log("리포트: $reportPath")
    }

    System.getenv(config.output.slackWebhookEnv)?.takeIf { it.isNotBlank() }?.let { url ->
        runCatching { Report.sendSlack(url, scored, config.output.minScoreToNotify, stats) }
            .onFailure { log("Slack 전송 실패: ${it.message}") }
    }

    // 실패·이월 공고가 있으면 수집 기준 시각을 당기지 않는다.
    // 다음 실행에서 같은 구간을 다시 조회하고, 처리 완료된 공고는 seen으로 걸러진다.
    if (stats.errors == 0 && stats.deferred == 0) {
        store.saveLastRunAt(runStartedAt)
    } else {
        log("미처리 공고가 남아 있어 수집 기준 시각을 유지합니다.")
    }
    store.flush()
    log("완료. ${stats.summaryLine()}")
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val m = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i].removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next != null && !next.startsWith("--") && a in setOf("config", "since-hours")) {
            m[a] = next; i += 2
        } else {
            m[a] = "true"; i++
        }
    }
    return m
}

private fun fail(msg: String): Nothing {
    System.err.println("오류: $msg")
    exitProcess(1)
}
