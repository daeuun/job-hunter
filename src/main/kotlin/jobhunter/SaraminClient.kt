package jobhunter

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

class SaraminApiException(message: String, val dailyLimitExceeded: Boolean = false) : RuntimeException(message)

/**
 * 사람인 공식 채용공고 API (https://oapi.saramin.co.kr/guide/job-search)
 * - 하루 최대 500회 호출
 * - count 최대 110, start는 0부터
 */
class SaraminClient(
    private val config: SaraminConfig,
    private val accessKey: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
    private val baseUrl: String = "https://oapi.saramin.co.kr/job-search",
) {
    var callCount = 0
        private set

    /** 키워드별로 publishedMin 이후 등록된 공고를 모두 모아 id 기준으로 중복 제거해 반환한다. */
    fun fetchSince(publishedMin: Instant): List<JobPosting> {
        val byId = LinkedHashMap<String, JobPosting>()
        for (keyword in config.keywords) {
            var page = 0
            while (page < config.maxPagesPerKeyword) {
                val (jobs, total) = fetchPage(keyword, publishedMin, page)
                jobs.forEach { byId.putIfAbsent(it.id, it) }
                log("  사람인 '$keyword' page=$page → ${jobs.size}건 (total=$total)")
                if (jobs.size < PAGE_SIZE || (page + 1) * PAGE_SIZE >= total) break
                page++
            }
        }
        return byId.values.toList()
    }

    private fun fetchPage(keyword: String, publishedMin: Instant, page: Int): Pair<List<JobPosting>, Int> {
        val params = linkedMapOf(
            "access-key" to accessKey,
            "keywords" to keyword,
            "published_min" to publishedMin.epochSecond.toString(),
            "sort" to "pd",
            "count" to PAGE_SIZE.toString(),
            "start" to page.toString(),
            "fields" to "posting-date,expiration-date",
        )
        if (config.locMcd.isNotBlank()) params["loc_mcd"] = config.locMcd
        if (config.jobMidCd.isNotBlank()) params["job_mid_cd"] = config.jobMidCd
        if (config.excludeDirectHire) params["sr"] = "directhire"

        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
        val req = HttpRequest.newBuilder(URI.create("$baseUrl?$query"))
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(20))
            .GET().build()
        callCount++
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        val body = mapper.readTree(res.body())

        // 에러 응답: {"code": 1~4|99, "message": "..."}
        if (body.has("code") && !body.has("jobs")) {
            val code = body.path("code").asInt()
            throw SaraminApiException(
                "사람인 API 오류 code=$code: ${body.path("message").asText()}",
                dailyLimitExceeded = code == 4,
            )
        }
        if (res.statusCode() != 200) {
            throw SaraminApiException("사람인 API HTTP ${res.statusCode()}: ${res.body().take(300)}")
        }

        val jobsNode = body.path("jobs")
        val total = jobsNode.path("total").asText("0").toIntOrNull() ?: 0
        val jobArr = jobsNode.path("job")
        val items = when {
            jobArr.isArray -> jobArr.toList()
            jobArr.isObject -> listOf(jobArr) // 결과 1건일 때 객체로 오는 경우 방어
            else -> emptyList()
        }
        return items.map(JobPosting::fromSaramin) to total
    }

    companion object {
        const val PAGE_SIZE = 110
    }
}
