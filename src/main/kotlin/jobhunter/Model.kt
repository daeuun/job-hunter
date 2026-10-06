package jobhunter

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val KST: ZoneId = ZoneId.of("Asia/Seoul")

/** 사람인 API 경력 코드: 0 무관, 1 신입, 2 경력, 3 신입/경력 */
data class Experience(val code: Int, val min: Int, val max: Int, val label: String)

data class JobPosting(
    val id: String,
    val url: String,
    val active: Boolean,
    val title: String,
    val company: String,
    val location: String,
    val jobType: String,
    val jobCodes: String,
    val experience: Experience,
    val education: String,
    val keywords: String,
    val salary: String,
    val closeType: String,
    val postedAt: Instant?,
    val expiresAt: Instant?,
) {
    fun deadlineLabel(): String {
        val exp = expiresAt
        // close-type이 '채용시/상시/수시'면 마감 timestamp가 먼 미래값이라 그대로 쓰지 않는다
        if (exp == null || closeType.isNotBlank() && closeType != "접수마감일") {
            return closeType.ifBlank { "-" }
        }
        return DateTimeFormatter.ofPattern("MM/dd").withZone(KST).format(exp)
    }

    companion object {
        /** 사람인 job-search JSON의 job 항목 하나를 파싱한다. */
        fun fromSaramin(n: JsonNode): JobPosting {
            val pos = n.path("position")
            val exp = pos.path("experience-level")
            fun ts(field: String): Instant? =
                n.path(field).asText("").toLongOrNull()?.let { Instant.ofEpochSecond(it) }
            return JobPosting(
                id = n.path("id").asText(),
                url = n.path("url").asText(),
                active = n.path("active").asInt(1) == 1,
                title = pos.path("title").asText("").trim(),
                company = n.path("company").path("detail").path("name").asText("").trim(),
                location = pos.path("location").path("name").asText("").replace("&gt;", ">"),
                jobType = pos.path("job-type").path("name").asText(""),
                jobCodes = pos.path("job-code").path("name").asText(""),
                experience = Experience(
                    code = exp.path("code").asInt(0),
                    min = exp.path("min").asInt(0),
                    max = exp.path("max").asInt(0),
                    label = exp.path("name").asText(""),
                ),
                education = pos.path("required-education-level").path("name").asText(""),
                keywords = n.path("keyword").asText(""),
                salary = n.path("salary").path("name").asText(""),
                closeType = n.path("close-type").path("name").asText(""),
                postedAt = ts("posting-timestamp"),
                expiresAt = ts("expiration-timestamp"),
            )
        }
    }
}

data class JobDetail(
    val text: String,
    val imagePaths: List<java.nio.file.Path>,
    val sourceUrl: String,
)

data class ScoredJob(
    val job: JobPosting,
    val score: Int,
    val verdict: String,
    val summary: String,
    val missing: List<String>,
    val risks: List<String>,
    val raw: JsonNode,
)
