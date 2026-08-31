// 이 파일: 클라우드 LLM 질의 전 식별번호 마스킹 (2026-08-31) — 사진 속 텍스트(OCR 원문)를
// 서버로 보내기 전에 주민등록번호 등 식별자를 [주민등록번호] 같은 토큰으로 치환한다.
// 주민등록번호는 개인정보보호법 제24조의2에 따라 정보주체 동의로도 처리할 수 없으므로
// 이 마스킹은 옵션이 아니라 전송 경로의 필수 관문이다. 발화문 이름 가림은 [CloudTextRedactor],
// 여기는 문서·안내문 본문의 번호류 담당. android.* 의존 없음 — JVM 단위 테스트 대상.
package com.example.snap_sight.privacy

internal object DocumentTextRedactor {

    /** @property maskedCount 치환된 식별자 수 — 0 이면 원문 그대로다 (동의 UI·로그용). */
    data class Result(val text: String, val maskedCount: Int) {
        val anyMasked: Boolean get() = maskedCount > 0
    }

    private class Rule(val label: String, pattern: String) {
        val regex = Regex(pattern)
    }

    // 순서 중요: 구체적인 패턴을 먼저 — 마지막의 일반 규칙(10자리+ 숫자열)이 앞 패턴이 잡을
    // 부분을 다른 라벨로 삼키지 않게 한다. 치환 토큰에는 숫자가 없어 뒤 규칙과 재충돌하지 않는다.
    // 과잉 마스킹은 감수한다(fail-safe) — 놓치는 쪽이 법적 리스크다.
    private val RULES = listOf(
        // 주민등록번호·외국인등록번호: 생년 6자리 + 성별/국적 코드(1~8)로 시작하는 7자리.
        // OCR 이 하이픈을 다른 대시로 읽는 경우 대비 [-–—], 붙여 쓴 13자리도 잡는다.
        Rule("주민등록번호", """(?<!\d)\d{6}\s*[-–—]?\s*[1-8]\d{6}(?!\d)"""),
        // 운전면허번호 숫자 형식: 12-34-567890-12
        Rule("운전면허번호", """(?<!\d)\d{2}\s*-\s*\d{2}\s*-\s*\d{6}\s*-\s*\d{2}(?!\d)"""),
        // 대한민국 여권번호: M/S/R/O/D + 8자리
        Rule("여권번호", """(?<![A-Za-z0-9])[MSRODmsrod]\d{8}(?!\d)"""),
        // 카드번호 4-4-4-4 (구분자 있는 형태 — 16자리 연속은 아래 일반 규칙이 잡는다)
        Rule("카드번호", """(?<!\d)\d{4}([ -]\d{4}){3}(?!\d)"""),
        Rule("사업자등록번호", """(?<!\d)\d{3}-\d{2}-\d{5}(?!\d)"""),
        // 휴대폰·지역번호 전화
        Rule("전화번호", """(?<!\d)0\d{1,2}[ -]?\d{3,4}[ -]?\d{4}(?!\d)"""),
        Rule("이메일", """[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"""),
        // 폴백: 공백/하이픈 구분 포함 총 10자리 이상 숫자열 — 형식이 제각각인 계좌번호 등.
        // 날짜(8자리)·금액(쉼표 구분)은 걸리지 않는다.
        Rule("번호", """(?<!\d)(?:\d[ -]?){9,}\d(?!\d)"""),
    )

    /** [text] 의 식별번호를 라벨 토큰으로 치환해 돌려준다. 매칭이 없으면 원문 그대로. */
    fun redact(text: String): Result {
        var out = text
        var count = 0
        for (rule in RULES) {
            out = rule.regex.replace(out) {
                count++
                "[${rule.label}]"
            }
        }
        return Result(out, count)
    }
}
