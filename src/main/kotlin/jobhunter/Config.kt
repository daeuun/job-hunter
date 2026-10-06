package jobhunter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path

val mapper: ObjectMapper = ObjectMapper()

data class SaraminConfig(
    val accessKeyEnv: String,
    /** 키워드별로 따로 호출한다. 사람인 API는 AND/OR 연산을 지원하지 않는다. */
    val keywords: List<String>,
    /** 1차 지역 코드(복수는 쉼표). 사람인 코드표 참고. 빈 값이면 미적용 */
    val locMcd: String,
    /** 상위 직무 코드. 사람인 코드표 참고. 빈 값이면 미적용 */
    val jobMidCd: String,
    val excludeDirectHire: Boolean,
    val maxPagesPerKeyword: Int,
    /** 첫 실행 시 몇 시간 전 공고부터 가져올지 */
    val initialLookbackHours: Long,
)

data class FilterConfig(
    val myExperienceYears: Int,
    /** 요구 최소경력이 내 경력 + stretch 이하면 통과 */
    val experienceStretchYears: Int,
    val excludeNewcomerOnly: Boolean,
    val excludeJobTypeKeywords: List<String>,
    val excludeTitleKeywords: List<String>,
    /** 제목·키워드·직무명 중 하나라도 포함해야 통과. 빈 리스트면 미적용 */
    val requireAnyKeyword: List<String>,
)

data class CrawlerConfig(
    val delayMillis: Long,
    val maxDetailsPerRun: Int,
    val userAgent: String,
    /** 본문 텍스트가 이 길이보다 짧고 이미지가 있으면 이미지 JD로 판단 */
    val minTextLength: Int,
    val maxImages: Int,
)

data class ClaudeConfig(
    val bin: String,
    val model: String,
    val timeoutSeconds: Long,
)

data class ProfileConfig(
    val resumePath: String,
    /** 희망 조건 자유 서술 (지역, 회사 유형 등) */
    val preferences: String,
)

data class OutputConfig(
    val dataDir: String,
    val minScoreToNotify: Int,
    val slackWebhookEnv: String,
)

data class AppConfig(
    val saramin: SaraminConfig,
    val filter: FilterConfig,
    val crawler: CrawlerConfig,
    val claude: ClaudeConfig,
    val profile: ProfileConfig,
    val output: OutputConfig,
) {
    companion object {
        fun load(path: Path): AppConfig {
            require(Files.exists(path)) { "설정 파일이 없습니다: $path (config.example.json을 복사해서 만드세요)" }
            val n = mapper.readTree(path.toFile())
            fun JsonNode.req(name: String): JsonNode =
                get(name) ?: error("설정 누락: $name")
            fun JsonNode.strList(name: String): List<String> =
                get(name)?.map { it.asText() } ?: emptyList()

            val s = n.req("saramin")
            val f = n.req("filter")
            val c = n.req("crawler")
            val cl = n.req("claude")
            val p = n.req("profile")
            val o = n.req("output")
            return AppConfig(
                saramin = SaraminConfig(
                    accessKeyEnv = s.path("accessKeyEnv").asText("SARAMIN_ACCESS_KEY"),
                    keywords = s.strList("keywords").ifEmpty { error("saramin.keywords가 비어 있습니다") },
                    locMcd = s.path("locMcd").asText(""),
                    jobMidCd = s.path("jobMidCd").asText(""),
                    excludeDirectHire = s.path("excludeDirectHire").asBoolean(true),
                    maxPagesPerKeyword = s.path("maxPagesPerKeyword").asInt(3),
                    initialLookbackHours = s.path("initialLookbackHours").asLong(72),
                ),
                filter = FilterConfig(
                    myExperienceYears = f.req("myExperienceYears").asInt(),
                    experienceStretchYears = f.path("experienceStretchYears").asInt(1),
                    excludeNewcomerOnly = f.path("excludeNewcomerOnly").asBoolean(true),
                    excludeJobTypeKeywords = f.strList("excludeJobTypeKeywords"),
                    excludeTitleKeywords = f.strList("excludeTitleKeywords"),
                    requireAnyKeyword = f.strList("requireAnyKeyword"),
                ),
                crawler = CrawlerConfig(
                    delayMillis = c.path("delayMillis").asLong(3000),
                    maxDetailsPerRun = c.path("maxDetailsPerRun").asInt(40),
                    userAgent = c.path("userAgent").asText(
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
                    ),
                    minTextLength = c.path("minTextLength").asInt(300),
                    maxImages = c.path("maxImages").asInt(3),
                ),
                claude = ClaudeConfig(
                    bin = cl.path("bin").asText("claude"),
                    model = cl.path("model").asText("sonnet"),
                    timeoutSeconds = cl.path("timeoutSeconds").asLong(240),
                ),
                profile = ProfileConfig(
                    resumePath = p.path("resumePath").asText("resume.md"),
                    preferences = p.path("preferences").asText(""),
                ),
                output = OutputConfig(
                    dataDir = o.path("dataDir").asText("data"),
                    minScoreToNotify = o.path("minScoreToNotify").asInt(70),
                    slackWebhookEnv = o.path("slackWebhookEnv").asText("SLACK_WEBHOOK_URL"),
                ),
            )
        }
    }
}
