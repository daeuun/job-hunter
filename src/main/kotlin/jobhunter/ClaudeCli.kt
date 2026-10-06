package jobhunter

import com.fasterxml.jackson.core.json.JsonWriteFeature
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class ClaudeCliException(message: String) : RuntimeException(message)

/**
 * 설치된 Claude Code CLI를 비대화형(-p)으로 실행해 구조화된 JSON을 받는다.
 *
 * 인증: `claude setup-token`으로 발급한 토큰을 CLAUDE_CODE_OAUTH_TOKEN 환경변수로 넘기거나,
 * 같은 머신에서 `claude`로 로그인해 둔 구독 세션을 그대로 쓴다.
 * 구독 인증을 쓰도록 자식 프로세스에서는 ANTHROPIC_API_KEY를 제거한다.
 *
 * 격리: 매 호출을 빈 작업 디렉터리에서 실행하고 MCP·도구를 끈다.
 * 이미지 JD가 있을 때만 그 디렉터리 안의 파일을 읽도록 Read 도구를 허용한다.
 */
class ClaudeCli(private val config: ClaudeConfig) {

    fun runStructured(
        systemPrompt: String,
        input: String,
        schema: JsonNode,
        workDir: Path,
        allowReadFiles: Boolean = false,
    ): JsonNode {
        Files.createDirectories(workDir)
        // 프로세스 인자는 JVM의 sun.jnu.encoding으로 인코딩된다. cron처럼 LANG이 비어 있으면
        // 한글이 '?'로 깨지므로, 인자는 ASCII만 쓰고 한글은 파일(시스템 프롬프트)과 stdin(입력)으로 넘긴다.
        val systemPromptFile = workDir.resolve(".system-prompt.md")
        Files.writeString(systemPromptFile, systemPrompt)
        val schemaAscii = mapper.writer()
            .with(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature())
            .writeValueAsString(schema)

        val cmd = listOf(
            config.bin, "-p",
            "Process the input given on stdin as instructed by the system prompt. Reply only with the required JSON schema.",
            "--output-format", "json",
            "--json-schema", schemaAscii,
            "--model", config.model,
            "--system-prompt-file", systemPromptFile.toAbsolutePath().toString(),
            "--tools", if (allowReadFiles) "Read" else "",
            "--disallowedTools", "mcp__*",
            "--strict-mcp-config",
            "--permission-mode", "dontAsk",
            "--no-session-persistence",
        )

        val stdout = workDir.resolve(".claude-stdout.json")
        val stderr = workDir.resolve(".claude-stderr.log")
        val pb = ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
        pb.environment().remove("ANTHROPIC_API_KEY")

        val proc = pb.start()
        proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(input) }
        if (!proc.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw ClaudeCliException("claude 실행 시간 초과(${config.timeoutSeconds}s)")
        }

        val out = Files.readString(stdout)
        val err = Files.readString(stderr)
        if (out.isBlank()) {
            throw ClaudeCliException("claude 출력 없음 (exit=${proc.exitValue()}): ${err.take(500)}")
        }
        val result = runCatching { mapper.readTree(out) }.getOrElse {
            throw ClaudeCliException("claude 출력이 JSON이 아님 (exit=${proc.exitValue()}): ${out.take(500)}")
        }
        if (proc.exitValue() != 0 || result.path("is_error").asBoolean(false)) {
            throw ClaudeCliException(
                "claude 실패 (exit=${proc.exitValue()}, subtype=${result.path("subtype").asText()}): " +
                    result.path("result").asText(err).take(500)
            )
        }

        // --json-schema 사용 시 structured_output에 담겨 온다. 없으면 result 텍스트에서 JSON을 복구한다.
        val structured = result.get("structured_output")
        if (structured != null && structured.isObject) return structured
        return extractJson(result.path("result").asText(""))
            ?: throw ClaudeCliException("구조화된 출력을 찾지 못함: ${result.path("result").asText().take(300)}")
    }

    companion object {
        fun extractJson(text: String): JsonNode? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { mapper.readTree(text.substring(start, end + 1)) }
                .getOrNull()?.takeIf { it.isObject }
        }
    }
}
