package jobhunter

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 사람인 공고 상세 본문 수집.
 * 공고 페이지의 본문은 iframe(view-detail)으로 분리돼 있어서
 * 1) 공고 페이지에서 iframe src를 찾고 2) 그 URL의 정적 HTML에서 본문을 추출한다.
 *
 * 개인 열람 용도로만, 요청 간 delayMillis 간격을 두고 호출한다.
 */
class DetailCrawler(
    private val config: CrawlerConfig,
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
    private val siteBase: String = "https://www.saramin.co.kr",
) {
    private var lastRequestAt = 0L

    fun fetch(job: JobPosting, imageDir: Path): JobDetail {
        val viewUrl = "$siteBase/zf_user/jobs/relay/view?rec_idx=${job.id}"
        val viewHtml = get(viewUrl, referer = siteBase)
        val viewDoc = Jsoup.parse(viewHtml, viewUrl)

        val iframeSrc = viewDoc.selectFirst("iframe[src*=view-detail]")?.absUrl("src")
        val detailUrl = iframeSrc?.takeIf { it.isNotBlank() }
            ?: "$siteBase/zf_user/jobs/relay/view-detail?rec_idx=${job.id}&rec_seq=0"

        val detailDoc = Jsoup.parse(get(detailUrl, referer = viewUrl), detailUrl)
        detailDoc.select("script, style, noscript").remove()
        val root = detailDoc.selectFirst(".user_content") ?: detailDoc.body()

        val text = readableText(root)
        val imageUrls = root.select("img[src]")
            .map { it.absUrl("src") }
            .filter { it.startsWith("http") && !it.endsWith(".gif") }
            .distinct()

        // 본문이 대부분 이미지인 공고는 이미지를 받아 LLM이 직접 읽게 한다
        val images = if (text.length < config.minTextLength && imageUrls.isNotEmpty()) {
            Files.createDirectories(imageDir)
            imageUrls.take(config.maxImages).mapIndexedNotNull { i, url ->
                runCatching { download(url, imageDir.resolve("jd_$i${extOf(url)}"), referer = detailUrl) }
                    .onFailure { log("    이미지 다운로드 실패: $url (${it.message})") }
                    .getOrNull()
            }
        } else emptyList()

        return JobDetail(text = text, imagePaths = images, sourceUrl = detailUrl)
    }

    private fun throttle() {
        val wait = lastRequestAt + config.delayMillis - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private fun request(url: String, referer: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", config.userAgent)
            .header("Accept-Language", "ko-KR,ko;q=0.9")
            .header("Referer", referer)
            .timeout(Duration.ofSeconds(20))

    private fun get(url: String, referer: String): String {
        throttle()
        val res = http.send(request(url, referer).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "HTTP ${res.statusCode()} $url" }
        return res.body()
    }

    private fun download(url: String, target: Path, referer: String): Path {
        throttle()
        val res = http.send(request(url, referer).GET().build(), HttpResponse.BodyHandlers.ofFile(target))
        check(res.statusCode() == 200) { "HTTP ${res.statusCode()}" }
        return target
    }

    private fun extOf(url: String): String {
        val path = URI.create(url).path.lowercase()
        return listOf(".png", ".jpg", ".jpeg", ".webp").firstOrNull { path.endsWith(it) } ?: ".jpg"
    }

    companion object {
        private val BLOCK_TAGS = setOf(
            "p", "div", "li", "tr", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "table", "section", "dt", "dd", "td", "th",
        )

        /** 블록 요소 경계에 줄바꿈을 넣어 사람이 읽을 수 있는 텍스트로 만든다. */
        fun readableText(root: Element): String {
            val sb = StringBuilder()
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> sb.append(node.text())
                        node is Element && node.normalName() in BLOCK_TAGS -> sb.append('\n')
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.normalName() in BLOCK_TAGS) sb.append('\n')
                }
            }, root)
            return sb.toString()
                .replace(' ', ' ')
                .lines()
                .map { it.replace(Regex("[ \\t]+"), " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
        }
    }
}
