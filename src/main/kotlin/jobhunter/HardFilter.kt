package jobhunter

/** LLM 호출 전에 메타데이터만으로 걸러내는 1차 필터. 제외 사유를 함께 돌려준다. */
class HardFilter(private val config: FilterConfig) {

    fun reject(job: JobPosting): String? {
        if (!job.active) return "마감된 공고"

        // "정규직,계약직"처럼 정규직이 함께 열려 있으면 통과시킨다
        if (!job.jobType.contains("정규직")) {
            config.excludeJobTypeKeywords.firstOrNull { job.jobType.contains(it) }?.let {
                return "근무형태 제외($it): ${job.jobType}"
            }
        }
        config.excludeTitleKeywords.firstOrNull { job.title.contains(it, ignoreCase = true) }?.let {
            return "제목 제외 키워드($it)"
        }

        val exp = job.experience
        if (config.excludeNewcomerOnly && exp.code == 1) return "신입 전용"
        if (exp.code == 2 && exp.min > config.myExperienceYears + config.experienceStretchYears) {
            return "요구 경력 초과(${exp.label})"
        }
        // 경력 상한이 있고 내 경력보다 낮으면 제외 (max=0은 상한 없음)
        if (exp.code == 2 && exp.max in 1 until config.myExperienceYears) {
            return "경력 상한 미달(${exp.label})"
        }

        if (config.requireAnyKeyword.isNotEmpty()) {
            val haystack = "${job.title} ${job.keywords} ${job.jobCodes}"
            if (config.requireAnyKeyword.none { haystack.contains(it, ignoreCase = true) }) {
                return "필수 키워드 없음"
            }
        }
        return null
    }
}
