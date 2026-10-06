package jobhunter

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * 실행 상태와 처리한 공고 ID를 JSON 파일로 관리한다.
 * - state.json: 마지막 성공 실행 시각 (다음 실행의 published_min 기준)
 * - seen.json : 처리 완료된 공고 ID → {at, status, score}. 실패한 공고는 기록하지 않아 다음 실행에 재시도된다.
 */
class Store(private val dataDir: Path) {
    private val statePath = dataDir.resolve("state.json")
    private val seenPath = dataDir.resolve("seen.json")
    private val seen: ObjectNode

    init {
        Files.createDirectories(dataDir)
        seen = if (Files.exists(seenPath)) mapper.readTree(seenPath.toFile()) as ObjectNode
        else mapper.createObjectNode()
    }

    fun lastRunAt(): Instant? =
        if (Files.exists(statePath)) {
            mapper.readTree(statePath.toFile()).path("lastRunEpoch").asLong(0).takeIf { it > 0 }
                ?.let(Instant::ofEpochSecond)
        } else null

    fun saveLastRunAt(at: Instant) {
        val node = mapper.createObjectNode().put("lastRunEpoch", at.epochSecond)
        mapper.writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), node)
    }

    fun isSeen(id: String) = seen.has(id)

    fun markSeen(id: String, status: String, score: Int? = null) {
        val n = mapper.createObjectNode().put("at", Instant.now().epochSecond).put("status", status)
        if (score != null) n.put("score", score)
        seen.set<ObjectNode>(id, n)
    }

    /** 오래된 기록을 정리하고 저장한다. */
    fun flush(retention: Duration = Duration.ofDays(90)) {
        val cutoff = Instant.now().minus(retention).epochSecond
        val stale = seen.properties().filter { it.value.path("at").asLong() < cutoff }.map { it.key }
        stale.forEach { seen.remove(it) }
        mapper.writerWithDefaultPrettyPrinter().writeValue(seenPath.toFile(), seen)
    }
}
