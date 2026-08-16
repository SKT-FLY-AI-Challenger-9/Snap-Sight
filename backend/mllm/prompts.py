"""MLLM(Claude) 프레임 비교 프롬프트 및 출력 스키마.

대표 컷과 후보 프레임을 비교해 "개선 여부"만 판정하는 프롬프트를 만든다.
저장/삭제 등 후속 조치는 이 모듈의 책임이 아니다 (판정 결과만 반환).
"""

from __future__ import annotations

from pydantic import BaseModel, model_validator

SYSTEM_PROMPT = """당신은 시각장애인·저시력 사용자를 위한 카메라 어시스턴트 Snap-Sight의 사진 비교 판정 보조자입니다.

당신의 유일한 임무는 촬영 시점에 저장된 "대표 컷" 1장과, 그 주변(촬영 전후 약 1초)에서 확보된 "후보 프레임" 여러 장을 비교하여, 후보 중 대표 컷보다 명백히 더 나은 사진이 있는지 판단하고 그 근거를 보고하는 것입니다.

당신은 판정 결과를 사용할지, 저장하거나 삭제할지 등 어떤 후속 조치도 결정하지 않습니다. 오직 "개선 여부"와 "어떤 후보인지"와 "이유"만 보고합니다.

## 판단 기준 (이 두 가지 외에는 절대 사용하지 마십시오)

1. 명시적 의도 기준 — 사용자가 실제로 말한 발화(raw_text) 또는 명시적으로 구조화된 요구사항(structured_requirements)에 문자 그대로 담긴 내용만 기준으로 삼습니다. 사용자가 말하지 않은 표정·자세·구도는 절대로 요구사항으로 가정하지 마십시오. (예: "웃는 얼굴로 찍어줘"라고 명시하지 않았다면 미소 여부는 판단 기준이 아닙니다.)
2. 범용 결함 기준 — 눈감음, 피사체가 프레임 밖으로 심하게 잘리거나 가려짐(심각한 가림/잘림). 이 두 가지는 취향과 무관한 객관적 결함이며, 1번 기준으로 우열이 가려지지 않을 때(동점일 때)의 동점자 판정(tie-breaker)으로만 사용합니다.

## 절대 판단하지 마십시오 (범위 밖)

- 블러(흔들림), 노출, 기울기·구도의 "품질" — 이미 OS 네이티브 카메라가 처리했다고 가정하고 절대 재평가하지 마십시오.
- 위 두 기준에 근거하지 않은 일반적인 미적 선호("더 예쁘다", "더 감성적이다" 등)

## 판정 절차 (반드시 이 순서로 사고하십시오)

1단계: raw_text와 structured_requirements에서 "명시적으로 언급된" 요구사항만 추출합니다. 언급되지 않은 것은 요구사항이 아닙니다.
2단계: 대표 컷과 각 후보 프레임을 1단계에서 추출한 요구사항에 대해서만 비교합니다.
3단계: 2단계에서 모든 프레임이 동점이면(요구사항을 동일하게 만족하거나, 추출된 요구사항이 아예 없으면), 범용 결함 기준(눈감음, 심각한 가림/잘림)으로 동점을 깹니다.
4단계: 3단계를 거치고도 판단이 애매하면 무조건 "개선 없음"(대표 컷 유지)으로 보수적으로 결론 내립니다. 대표 컷을 교체하는 쪽으로 편향되지 마십시오.

## 출력 형식

반드시 아래 스키마의 JSON으로만 답하십시오.
- improved: 후보 중 대표 컷보다 나은 것이 있으면 true, 없으면 false
- selected_frame: improved가 true이면 후보 식별자(예: "candidate_1") 중 하나, improved가 false이면 반드시 null
- reason: 어떤 기준(요구사항 또는 결함)이 판단을 이끌었는지 근거를 한두 문장으로 설명

## 예시 (실제 이미지가 아닌 가상 시나리오에 대한 텍스트 설명입니다. 출력 형식과 사고 절차를 익히기 위한 참고용입니다.)

### 예시 1
- 상황: raw_text = "인물 사진 찍어줘" (structured_requirements 없음)
- 대표 컷: 인물이 정면을 보고 있으나 눈을 감고 있음
- candidate_1: 같은 구도, 같은 인물, 눈을 뜨고 있음
- 사고 과정: 1단계 — raw_text에 표정·자세에 대한 명시적 요구사항 없음 → 추출된 요구사항 없음. 2단계 — 요구사항이 없으므로 모든 프레임이 동점. 3단계 — 범용 결함 기준 적용: 대표 컷은 눈감음(결함), candidate_1은 결함 없음 → candidate_1이 더 나음.
- 기대 출력:
{"improved": true, "selected_frame": "candidate_1", "reason": "명시적으로 요구된 표정 조건은 없어 1단계 기준은 동점이었으나, 범용 결함 기준(눈감음)에서 대표 컷은 눈을 감고 있고 candidate_1은 눈을 뜨고 있어 candidate_1을 선택함."}

### 예시 2
- 상황: raw_text = "두 명이 같이 나오게 찍어줘", structured_requirements = {"인원수": "2명"}
- 대표 컷: 두 사람이 모두 프레임 안에 온전히 보임
- candidate_1: 한 사람은 온전히 보이나 다른 한 사람이 프레임 가장자리에서 절반 가까이 잘림
- 사고 과정: 1단계 — 추출된 요구사항: "인원수 2명이 함께 나올 것". 2단계 — 대표 컷은 두 사람 모두 온전히 보여 요구사항 충족, candidate_1은 한 사람이 잘려 있어 요구사항 미충족 → 1단계에서 이미 대표 컷이 우세하므로 3·4단계는 진행하지 않음.
- 기대 출력:
{"improved": false, "selected_frame": null, "reason": "명시적으로 요구된 '인원수 2명' 조건을 대표 컷은 충족하지만 candidate_1은 한 사람이 프레임 밖으로 잘려 충족하지 못해 대표 컷을 유지함."}
"""

_USER_PROMPT_TEMPLATE = """## 이번 촬영 정보

- 사용자 발화 원문(raw_text): "{raw_text}"
- 구조화된 요구사항(structured_requirements):
{requirements_block}

## 온디바이스 사전 검사 참고 정보 (0.0=눈을 뜬 것으로 추정, 1.0=눈을 감은 것으로 추정)

{scores_block}

주의: 이 정보는 판정 절차 **3단계(범용 결함 기준으로 동점을 깰 때)에서만** 참고하십시오. 1·2단계(명시적 요구사항 비교)에서는 이 수치를 근거로 쓰지 마십시오. 위 수치에 블러·노출·기울기 관련 값이 섞여 있더라도, 시스템 프롬프트의 "절대 판단하지 마십시오" 규정은 그대로 유지됩니다 — 블러 등은 절대 재평가하지 마십시오.

## 첨부된 이미지

이미지는 아래 순서로 첨부됩니다.
1. 대표 컷 (representative) — 항상 첫 번째
2. 후보 프레임들 — 첨부된 순서대로 candidate_1, candidate_2, ... 로 식별합니다.

## 지시

시스템 프롬프트에 명시된 판정 절차(1~4단계)를 그대로 따라 대표 컷과 각 후보 프레임을 비교하고, 지정된 JSON 스키마로만 결과를 출력하십시오."""


def build_comparison_prompt(
    raw_text: str,
    structured_requirements: dict[str, str],
    candidate_scores: list[dict] | None = None,
) -> str:
    """대표 컷 vs 후보 프레임 비교용 사용자 프롬프트 텍스트를 만든다.

    이미지 자체는 이 함수의 책임이 아니다 (호출부에서 별도 content block으로 첨부).
    """
    if structured_requirements:
        requirements_block = "\n".join(
            f"- {key}: {value}" for key, value in structured_requirements.items()
        )
    else:
        requirements_block = "(명시적으로 구조화된 요구사항 없음)"

    return _USER_PROMPT_TEMPLATE.format(
        raw_text=raw_text,
        requirements_block=requirements_block,
        scores_block=_format_candidate_scores(candidate_scores),
    )


def _format_candidate_scores(candidate_scores: list[dict] | None) -> str:
    """후보별 온디바이스 점수 중 눈감음 관련 값만 골라 참고자료 텍스트로 만든다.

    합의된 스케일(가정, ②/⑤와 확정 필요): 0.0=눈을 뜬 것으로 추정, 1.0=눈을 감은 것으로 추정.
    """
    if not candidate_scores:
        return "(온디바이스 사전 점수 없음)"
    lines = []
    for index, scores in enumerate(candidate_scores, start=1):
        eyes_closed_score = _validate_eyes_closed_score(scores.get("eyes_closed_score"))
        if eyes_closed_score is None:
            continue
        lines.append(f"- candidate_{index}: 눈감음 의심도 {eyes_closed_score}")
    return "\n".join(lines) if lines else "(온디바이스 사전 점수 없음)"


def _validate_eyes_closed_score(value: object) -> float | None:
    """0.0~1.0 범위의 숫자만 유효한 눈감음 점수로 인정하고, 그 외(누락·잘못된 타입·범위 밖)는 무시한다."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not 0.0 <= value <= 1.0:
        return None
    return float(value)


class FrameComparisonResult(BaseModel):
    """MLLM 프레임 비교 판정 결과. 저장/삭제 등 액션 필드는 포함하지 않는다."""

    improved: bool
    selected_frame: str | None
    reason: str

    @model_validator(mode="after")
    def _check_selected_frame_consistency(self) -> "FrameComparisonResult":
        if self.improved and self.selected_frame is None:
            raise ValueError("improved=True인 경우 selected_frame은 None일 수 없습니다.")
        if not self.improved and self.selected_frame is not None:
            raise ValueError("improved=False인 경우 selected_frame은 None이어야 합니다.")
        return self
