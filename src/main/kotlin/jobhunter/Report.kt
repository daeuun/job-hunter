package jobhunter

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

object Report {

    /** 실행 결과를 마크다운 리포트로 쓰고, 원본 평가는 jsonl에 누적한다. */
    fun write(dataDir: Path, scored: List<ScoredJob>, stats: RunStats): Path {
        val now = Instant.now()
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(KST).format(now)
        val dir = dataDir.resolve("reports")
        Files.createDirectories(dir)
        val path = dir.resolve("$stamp.md")

        val sorted = scored.sortedByDescending { it.score }
        val md = buildString {
            appendLine("# 채용공고 매칭 리포트 — ${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST).format(now)}")
            appendLine()
            appendLine(stats.summaryLine())
            appendLine()
            if (sorted.isEmpty()) appendLine("새로 평가된 공고가 없습니다.")
            for (s in sorted) {
                val j = s.job
                appendLine("## ${s.score}점 · ${s.verdict} — [${j.title}](${j.url})")
                appendLine("- **${j.company}** · ${j.location} · ${j.jobType} · ${j.experience.label} · 마감 ${j.deadlineLabel()}")
                if (j.salary.isNotBlank()) appendLine("- 연봉: ${j.salary}")
                appendLine("- ${s.summary}")
                if (s.missing.isNotEmpty()) appendLine("- 미충족 필수요건: ${s.missing.joinToString(", ")}")
                if (s.risks.isNotEmpty()) appendLine("- 리스크: ${s.risks.joinToString(", ")}")
                appendLine()
            }
        }
        Files.writeString(path, md)

        val jsonl = dataDir.resolve("results.jsonl")
        Files.newBufferedWriter(jsonl, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { w ->
            for (s in scored) {
                val row = mapper.createObjectNode()
                    .put("evaluatedAt", now.toString())
                    .put("id", s.job.id)
                    .put("title", s.job.title)
                    .put("company", s.job.company)
                    .put("url", s.job.url)
                row.set<com.fasterxml.jackson.databind.JsonNode>("evaluation", s.raw)
                w.write(mapper.writeValueAsString(row))
                w.newLine()
            }
        }
        return path
    }

    fun sendSlack(webhookUrl: String, scored: List<ScoredJob>, minScore: Int, stats: RunStats) {
        val top = scored.filter { it.score >= minScore }.sortedByDescending { it.score }
        if (top.isEmpty()) return
        val text = buildString {
            appendLine("*오늘의 매칭 공고 ${top.size}건* (${minScore}점 이상)")
            appendLine("_${stats.summaryLine()}_")
            for (s in top.take(15)) {
                val j = s.job
                appendLine("• *${s.score}점* <${j.url}|${j.title.replace(">", "")}> — ${j.company} · ${j.experience.label} · 마감 ${j.deadlineLabel()}")
                appendLine("   ${s.summary}")
                if (s.missing.isNotEmpty()) appendLine("   미충족: ${s.missing.joinToString(", ")}")
            }
        }
        val body = mapper.writeValueAsString(mapper.createObjectNode().put("text", text))
        val req = HttpRequest.newBuilder(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) log("Slack 전송 실패: HTTP ${res.statusCode()} ${res.body()}")
    }
}

data class RunStats(
    var fetched: Int = 0,
    var alreadySeen: Int = 0,
    var filtered: Int = 0,
    var scored: Int = 0,
    var errors: Int = 0,
    var deferred: Int = 0,
    var apiCalls: Int = 0,
) {
    fun summaryLine() =
        "수집 $fetched · 기처리 $alreadySeen · 필터 제외 $filtered · 평가 $scored · 실패 $errors · 다음 실행으로 이월 $deferred · 사람인 API 호출 ${apiCalls}회"
}
