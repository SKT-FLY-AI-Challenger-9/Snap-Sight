"""피사체 위치와 목표 프레이밍을 비교해서 카메라가 얼마나 벗어났는지 계산하는 모듈.

사용자가 인물을 클로즈업으로 찍어달라고 말하면, 그 말은 미리 closeup, full_body, wide
중 하나의 프레이밍 목표로 변환되어 이 모듈에 전달된다. 온디바이스 카메라 인식 기능이 매
프레임마다 피사체가 화면 어디에 얼마나 크게 잡혔는지 알려주면, 이 모듈은 그 값과 목표
프레이밍을 비교해서 위치와 크기가 얼마나 벗어났는지를 숫자로 계산한다.

이렇게 계산한 숫자는 나중에 진동이나 소리로 사용자에게 안내하는 데 쓰이지만, 그 안내를
만드는 것과 실제 서버 API를 만드는 것은 이 모듈이 하는 일이 아니다. 이 모듈은 순수하게
숫자만 계산한다.
"""

from __future__ import annotations

from pydantic import BaseModel, Field, model_validator

# 화면 가로 중심 위치. 어떤 프레이밍 목표든 피사체가 이 위치에 오는 것을 이상적으로 본다.
TARGET_CENTER_X = 0.5

# 프레이밍 종류별로 피사체 bbox가 화면 전체 면적에서 차지해야 하는 목표 비율이다.
# 아직 실제 사진으로 검증한 값이 아니라 처음 추정한 수치이며, 나중에 조정될 수 있다.
TARGET_AREA_RATIO: dict[str, float] = {
    "closeup": 0.30,
    "full_body": 0.12,
    "wide": 0.04,
}


class DetectionSignal(BaseModel):
    """온디바이스 카메라 인식 기능이 넘겨주는 값.

    피사체를 감싸는 사각형인 bbox 전체 좌표를 다 받지 않고, 편차 계산에 실제로
    필요한 두 값만 받는다. center_x는 피사체 중심이 화면 가로 방향 어디에 있는지를
    0에서 1 사이 비율로 나타낸 값이고, area_ratio는 피사체가 화면 전체에서 차지하는
    면적 비율이다.
    """

    center_x: float = Field(ge=0.0, le=1.0)
    area_ratio: float = Field(ge=0.0, le=1.0)


class DeviationResult(BaseModel):
    """편차 계산 결과.

    subject_detected가 거짓이면 피사체를 찾지 못했다는 뜻이므로 x_deviation과
    size_deviation은 반드시 비어 있어야 한다. subject_detected가 참이면 반대로
    두 값 모두 채워져 있어야 한다. 이 규칙이 깨지면 오류가 발생한다.
    """

    subject_detected: bool
    x_deviation: float | None
    size_deviation: float | None

    @model_validator(mode="after")
    def _check_deviation_consistency(self) -> "DeviationResult":
        fields = (self.x_deviation, self.size_deviation)
        if self.subject_detected and any(f is None for f in fields):
            raise ValueError("subject_detected가 True인 경우 편차 값은 비어 있으면 안 됩니다.")
        if not self.subject_detected and any(f is not None for f in fields):
            raise ValueError("subject_detected가 False인 경우 편차 값은 모두 비어 있어야 합니다.")
        return self


def calculate_deviation(detection: DetectionSignal | None, framing: str) -> DeviationResult:
    """현재 피사체 위치와 목표 프레이밍을 비교해서 편차를 계산한다.

    편차는 양수와 음수를 구분해서 반환한다. 그래야 나중에 왼쪽으로 이동하라는 식의
    구체적인 안내를 만들 때, 이 함수를 다시 고칠 필요 없이 반환값만 보고 방향을 알 수
    있다.

    x_deviation이 음수이면 피사체가 화면 중심보다 왼쪽에 있다는 뜻이고, 양수이면
    오른쪽에 있다는 뜻이다. size_deviation이 음수이면 피사체가 목표보다 작게 잡혀서
    카메라가 너무 멀리 있다는 뜻이고, 양수이면 목표보다 크게 잡혀서 너무 가깝다는 뜻이다.

    detection이 None으로 들어오면 피사체를 찾지 못한 것으로 처리한다. 이 경우는 두
    가지 상황을 모두 포함한다. 하나는 카메라 인식 기능이 실제로 피사체를 놓친 경우이고,
    다른 하나는 사용자가 애초에 풍경 사진처럼 특정 피사체 없이 찍으려는 경우다. 두 상황
    모두 이 함수 입장에서는 겨냥할 대상이 없다는 점에서 똑같이 취급한다.
    """
    if framing not in TARGET_AREA_RATIO:
        raise ValueError(f"알 수 없는 framing 값입니다: {framing!r}")

    if detection is None:
        return DeviationResult(subject_detected=False, x_deviation=None, size_deviation=None)

    return DeviationResult(
        subject_detected=True,
        x_deviation=detection.center_x - TARGET_CENTER_X,
        size_deviation=detection.area_ratio - TARGET_AREA_RATIO[framing],
    )
