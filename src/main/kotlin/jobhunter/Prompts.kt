package jobhunter

import com.fasterxml.jackson.databind.JsonNode

/** LLM 프롬프트와 출력 스키마. 점수 기준을 바꾸고 싶으면 이 파일만 고치면 된다. */
object Prompts {

    val PROFILE_SYSTEM = """
        너는 채용 매칭 시스템의 이력서 분석기다. 입력된 이력서를 구조화된 프로필로 변환한다.

        규칙:
        - 이력서에 명시된 사실만 기록한다. 추정·과장·일반화 금지.
        - 기술마다 사용 맥락을 구분한다: "실무"(회사 업무), "사이드프로젝트", "학습"(강의·개인 공부).
        - evidence에는 그 기술을 실제로 쓴 프로젝트/업무를 한 줄로 적는다. 근거가 없으면 그 기술을 넣지 않는다.
        - totalExperienceYears는 이력서에 적힌 재직 기간 기준 실무 경력(년, 소수 1자리)이다. 사이드프로젝트 기간은 포함하지 않는다.
    """.trimIndent()

    val PROFILE_SCHEMA: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "totalExperienceYears": {"type": "number"},
            "roles": {"type": "array", "items": {"type": "string"}},
            "skills": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "name": {"type": "string"},
                  "context": {"type": "string", "enum": ["실무", "사이드프로젝트", "학습"]},
                  "evidence": {"type": "string"}
                },
                "required": ["name", "context", "evidence"]
              }
            },
            "domains": {"type": "array", "items": {"type": "string"}},
            "highlights": {"type": "array", "items": {"type": "string"}}
          },
          "required": ["totalExperienceYears", "roles", "skills", "domains", "highlights"]
        }
        """
    )

    val SCORE_SYSTEM = """
        너는 백엔드 개발자 구직자를 위한 채용공고 매칭 평가자다.
        입력으로 [지원자 프로필], [희망 조건], [공고 메타데이터], [공고 본문]이 주어진다.
        본문 대신 이미지 파일 경로가 주어지면 Read 도구로 그 이미지를 읽고 내용을 본문으로 사용한다.

        1. 공고에서 필수요건(자격요건)과 우대사항을 항목별로 추출한다.
        2. 각 항목을 프로필과 대조해 met을 판정한다.
           - "충족": 프로필 skills/highlights에 직접적인 근거가 있다. evidence에 그 근거를 적는다.
           - "부분": 관련 경험은 있으나 맥락이 다르다(예: 공고는 실무 요구, 프로필은 사이드프로젝트만).
           - "미충족": 근거가 없다.
           - "확인불가": 공고 정보가 모호해서 판단할 수 없다.
           유사 기술로 넘겨짚지 않는다(Kotlin 경험 ≠ Go 경험). 관대하게 채점하지 않는다.
        3. 아래 루브릭으로 score(0~100 정수)를 계산한다.
           - 필수요건 충족도 50점: 충족 1, 부분 0.5, 미충족 0으로 환산한 비율 × 50. 확인불가는 분모에서 제외.
           - 핵심 기술 스택 일치 25점: 공고의 주력 언어/프레임워크가 프로필의 "실무" 스킬과 일치하는 정도.
           - 경력 범위 적합 15점: 요구 경력 범위 안이면 15, 1년 이내 차이면 8, 그 이상 차이면 0.
           - 우대사항 10점: 충족 비율 × 10.
           - 필수요건 중 핵심 기술 항목이 "미충족"이면 총점 상한 55.
        4. verdict: score ≥ 75 "추천", 55~74 "검토", < 55 "제외".
        5. summary는 지원 여부 판단에 필요한 핵심만 2문장 이내로 쓴다.
        6. 본문이 부족해 판단 근거가 약하면 risks에 "JD 정보 부족"을 넣는다.
           계약직·파견·SI 상주 등 희망 조건과 충돌하는 요소도 risks에 넣는다.
    """.trimIndent()

    private const val REQ_ITEM = """
        {
          "type": "object",
          "properties": {
            "requirement": {"type": "string"},
            "met": {"type": "string", "enum": ["충족", "부분", "미충족", "확인불가"]},
            "evidence": {"type": "string"}
          },
          "required": ["requirement", "met", "evidence"]
        }
    """

    val SCORE_SCHEMA: JsonNode = mapper.readTree(
        """
        {
          "type": "object",
          "properties": {
            "score": {"type": "integer", "minimum": 0, "maximum": 100},
            "verdict": {"type": "string", "enum": ["추천", "검토", "제외"]},
            "requiredExperience": {"type": "string"},
            "mustHave": {"type": "array", "items": $REQ_ITEM},
            "niceToHave": {"type": "array", "items": $REQ_ITEM},
            "summary": {"type": "string"},
            "risks": {"type": "array", "items": {"type": "string"}}
          },
          "required": ["score", "verdict", "requiredExperience", "mustHave", "niceToHave", "summary", "risks"]
        }
        """
    )
}
