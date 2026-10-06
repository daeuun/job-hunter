package jobhunter

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** 이력서 → 프로필 JSON. 이력서 내용이 바뀌었을 때만 다시 만든다. */
class ProfileBuilder(
    private val claude: ClaudeCli,
    private val resumePath: Path,
    private val cachePath: Path,
    private val workRoot: Path,
) {
    fun load(): JsonNode {
        require(Files.exists(resumePath)) { "이력서 파일이 없습니다: $resumePath" }
        val resume = Files.readString(resumePath)
        val hash = sha256(resume)

        if (Files.exists(cachePath)) {
            val cached = mapper.readTree(cachePath.toFile())
            if (cached.path("resumeHash").asText() == hash) return cached.path("profile")
        }

        log("이력서가 변경되어 프로필을 새로 생성합니다…")
        val profile = claude.runStructured(
            systemPrompt = Prompts.PROFILE_SYSTEM,
            input = "[이력서]\n$resume",
            schema = Prompts.PROFILE_SCHEMA,
            workDir = workRoot.resolve("profile"),
        )
        val wrapper = mapper.createObjectNode()
        wrapper.put("resumeHash", hash)
        wrapper.set<JsonNode>("profile", profile)
        Files.createDirectories(cachePath.parent)
        mapper.writerWithDefaultPrettyPrinter().writeValue(cachePath.toFile(), wrapper)
        return profile
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

class Scorer(
    private val claude: ClaudeCli,
    private val profile: JsonNode,
    private val preferences: String,
) {
    fun score(job: JobPosting, detail: JobDetail?, workDir: Path): ScoredJob {
        val meta = """
            제목: ${job.title}
            회사: ${job.company}
            지역: ${job.location}
            근무형태: ${job.jobType}
            경력: ${job.experience.label}
            학력: ${job.education}
            직무: ${job.jobCodes}
            키워드: ${job.keywords}
            연봉: ${job.salary}
            마감: ${job.deadlineLabel()}
        """.trimIndent()

        val body = when {
            detail == null -> "(본문 수집 실패 — 메타데이터만으로 판단하고 risks에 'JD 정보 부족'을 넣어라)"
            detail.imagePaths.isNotEmpty() ->
                "본문 텍스트(일부):\n${detail.text.take(2000)}\n\n본문 이미지 파일 (Read 도구로 읽어라):\n" +
                    detail.imagePaths.joinToString("\n") { "- ${it.fileName}" }
            else -> detail.text.take(12_000)
        }

        val input = buildString {
            appendLine("[지원자 프로필]")
            appendLine(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile))
            appendLine()
            appendLine("[희망 조건]")
            appendLine(preferences.ifBlank { "(없음)" })
            appendLine()
            appendLine("[공고 메타데이터]")
            appendLine(meta)
            appendLine()
            appendLine("[공고 본문]")
            appendLine(body)
        }

        val r = claude.runStructured(
            systemPrompt = Prompts.SCORE_SYSTEM,
            input = input,
            schema = Prompts.SCORE_SCHEMA,
            workDir = workDir,
            allowReadFiles = detail?.imagePaths?.isNotEmpty() == true,
        )

        val missing = r.path("mustHave")
            .filter { it.path("met").asText() == "미충족" }
            .map { it.path("requirement").asText() }
        return ScoredJob(
            job = job,
            score = r.path("score").asInt().coerceIn(0, 100),
            verdict = r.path("verdict").asText(),
            summary = r.path("summary").asText(),
            missing = missing,
            risks = r.path("risks").map { it.asText() },
            raw = r,
        )
    }
}
