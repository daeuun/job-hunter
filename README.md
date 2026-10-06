# job-hunter

사람인 신규 공고를 매일 수집하고, 내 이력서 기준으로 Claude가 매칭 점수를 매겨 리포트/Slack으로 보내는 개인용 도구입니다.

```
사람인 공식 API ──▶ 하드 필터 ──▶ 상세 본문 크롤링 ──▶ Claude Code CLI(claude -p) ──▶ 리포트 / Slack
(신규 공고 목록)     (경력·근무형태·   (필터 통과분만,     (이력서 프로필 × JD,
                     키워드)          요청 간 3초)        JSON 스키마 강제)
```

- **수집**: 사람인 오픈API `job-search`. 마지막 성공 실행 시각 이후 공고만 증분 조회합니다.
- **필터**: LLM 호출 전에 경력 범위, 근무형태(계약직·파견 등), 제외/필수 키워드로 1차 컷.
- **상세**: 필터 통과 공고만 상세 iframe(`view-detail`)의 정적 HTML에서 본문을 추출. 본문이 이미지뿐이면 이미지를 내려받아 Claude가 `Read` 도구로 직접 읽습니다.
- **평가**: 이력서 → 프로필 JSON(이력서가 바뀔 때만 재생성) → 공고별로 필수요건 충족/부분/미충족 판정 + 루브릭 점수.
- **상태**: `data/seen.json`으로 중복 제거. 평가 실패한 공고는 기록하지 않아 다음 실행에 재시도됩니다.

## 1. 준비

1. **Java 17+**
2. **Claude Code** 설치 후 토큰 발급
   ```bash
   npm install -g @anthropic-ai/claude-code
   claude setup-token   # Claude 구독 필요. 출력된 토큰을 .env의 CLAUDE_CODE_OAUTH_TOKEN에 저장
   ```
   같은 머신에서 이미 `claude`에 로그인돼 있으면 토큰 없이도 동작합니다.
3. **사람인 access-key**: https://oapi.saramin.co.kr 에서 이용신청 → 승인 → 키 발급 (하루 500회 호출 제한)

## 2. 설정

```bash
cp config.example.json config.json
cp .env.example .env          # 키/토큰 입력
vi resume.md                  # 이력서 본문을 마크다운/텍스트로 붙여넣기
```

| 항목 | 설명 |
| --- | --- |
| `saramin.keywords` | 키워드마다 따로 호출합니다 (API가 AND/OR 미지원). 비워도 되지만 `jobCd`/`jobMidCd`가 필요합니다 |
| `saramin.locations` | 지역 그룹별로 따로 호출합니다. 항목마다 `loc_cd`/`loc_mcd`/`loc_bcd` 중 하나만. 비우면 지역 필터 없음 |
| `saramin.jobMidCd`, `jobCd`, `indCd` | 직무·업종 코드. [사람인 코드표](https://oapi.saramin.co.kr/guide/code-table2)에서 확인 후 입력. 빈 값이면 미적용 |
| `saramin.maxPagesPerQuery` | (지역 × 키워드)당 최대 페이지 수. 도달하면 로그에 경고 |
| `filter.allowedLocationKeywords` | 근무지에 하나라도 포함돼야 통과. 빈 리스트면 미적용 |
| `filter.myExperienceYears` | **실무 재직 기간 기준** 경력(년). 사이드프로젝트 기간은 넣지 않습니다 |
| `filter.experienceStretchYears` | 요구 최소경력이 `내 경력 + N`년 이하면 통과 |
| `filter.excludeJobTypeKeywords` | 근무형태 제외어. 단, `정규직`이 함께 열린 공고는 통과 |
| `filter.requireAnyKeyword` | 제목·키워드·직무명 중 하나라도 포함해야 통과 |
| `crawler.delayMillis` | 상세 요청 간 간격(ms). 낮추지 마세요 |
| `crawler.maxDetailsPerRun` | 1회 실행당 평가 상한. 넘치는 공고는 다음 실행으로 이월 |
| `claude.model` | `sonnet` 권장 (`opus`, `haiku`도 가능) |
| `profile.preferences` | 희망 조건 자유 서술. 예: `서울/판교, 정규직, 자사 서비스 선호` |
| `output.minScoreToNotify` | Slack으로 보낼 최소 점수 |

점수 기준(루브릭)과 출력 스키마는 `src/main/kotlin/jobhunter/Prompts.kt`에 모여 있습니다.

## 3. 실행

```bash
./run.sh --dry-run          # 수집·필터·상세 크롤링까지만. LLM 호출 없음, 상태 저장 안 함
./run.sh                    # 전체 실행
./run.sh --since-hours 168  # 이번만 최근 7일 공고 재조회
./run.sh --no-detail        # 상세 크롤링 없이 API 메타데이터만으로 평가
```

결과
- `data/reports/YYYY-MM-DD_HHmm.md` — 점수순 리포트
- `data/results.jsonl` — 공고별 평가 원본(필수요건 항목별 판정 포함)
- `logs/YYYY-MM-DD.log`

## 4. 매일 자동 실행 (cron)

사람인이 데이터센터 IP를 차단하는 경우가 있어 **개인 PC에서 실행**하는 것을 권장합니다.

```bash
crontab -e
# 평일 08:47 실행
47 8 * * 1-5 /절대경로/job-hunter/run.sh >/dev/null 2>&1
```

`run.sh`가 `.env` 로드, PATH/LANG 보정, 최초 빌드(`installDist`)를 처리합니다.

## 인증 방식에 대해

이 도구는 Claude 구독 OAuth 토큰으로 API를 직접 호출하지 않고, **설치된 Claude Code CLI(`claude -p`)를 그대로 실행**합니다. Anthropic 문서상 구독 OAuth 인증은 Claude Code 등 Anthropic 앱의 통상적인 사용을 위한 것이고, 서드파티 앱이 구독 자격증명으로 요청을 직접 라우팅하는 것은 허용되지 않습니다. 개인 PC에서 본인 구독으로 하루 1회 수십 건 평가하는 정도의 개인 자동화 용도로 쓰세요. 다른 사람에게 서비스로 제공하려면 Claude Console API 키를 써야 합니다.

- 참고: https://code.claude.com/docs/en/legal-and-compliance

호출은 매번 빈 작업 디렉터리에서 MCP·도구를 끈 상태로 실행합니다(이미지 JD일 때만 `Read` 허용). 자식 프로세스에서는 `ANTHROPIC_API_KEY`를 제거해 구독 인증이 쓰이도록 합니다.

## 크롤링 주의

- 상세 본문 크롤링은 개인 열람 용도로만 사용하고, 수집 데이터를 재배포하지 마세요.
- 요청 간격을 유지하고, 하루 1회 실행을 권장합니다.
- 사람인 상세 페이지 구조가 바뀌면 본문 추출이 실패할 수 있습니다. 이 경우 로그에 `상세 수집 실패`가 찍히고, 메타데이터만으로 평가하며 리포트에 `JD 정보 부족` 리스크가 표시됩니다.

## 구조

```
src/main/kotlin/jobhunter/
├── Main.kt           # 실행 흐름 (수집 → 필터 → 상세 → 평가 → 리포트)
├── Config.kt         # config.json 로딩
├── Model.kt          # JobPosting, 사람인 응답 파싱
├── SaraminClient.kt  # 사람인 오픈API (증분 조회, 페이지네이션, 에러 코드)
├── HardFilter.kt     # 1차 필터
├── DetailCrawler.kt  # 상세 iframe 본문/이미지 수집
├── ClaudeCli.kt      # claude -p 실행 (JSON 스키마, 격리)
├── Prompts.kt        # 프롬프트·루브릭·스키마
├── Scorer.kt         # 이력서 프로필 캐시, 공고 평가
├── Store.kt          # state/seen 저장
└── Report.kt         # 마크다운 리포트, Slack
```

## 확장 포인트

- **잡코리아/점핏**: `JobPosting`을 반환하는 수집기를 추가하고 `Main.kt`에서 결과를 합치면 됩니다. 나머지 파이프라인은 그대로 씁니다.
- **Spring Boot로 이전**: `Main.kt`의 흐름을 `@Scheduled` 서비스로 옮기고, `Store`를 DB로 바꾸면 됩니다.
