# ai/tools/export_object_embedder.py
"""사물 외형 임베더용 MobileNetV3-Small 특징 추출기를 .tflite 로 내보낸다 (2026-08-23).

수제 색·윤곽 히스토그램 임베더(LocalObjectAppearanceEmbedder)는 봉제인형처럼 질감이
비슷한 물건들에서 유사도가 0.9+ 로 포화돼 구분이 불가능했다 (실기기 로그로 확인).
ImageNet 사전학습 심층 특징으로 교체한다 — Kotlin 쪽 계약은 ObjectEmbedder 그대로이고
DB 는 modelId 로 분리돼 있어 재등록만 하면 된다.

사용:
    python -m ai.tools.export_object_embedder

출력: frontend/app/src/main/assets/object_embedder.tflite (float16, 약 2~3MB)
 - 입력: [1, 224, 224, 3] float32, 픽셀값 0..255 (전처리 모델 내장 include_preprocessing=True)
 - 출력: [1, 576] float32 특징 벡터 (Kotlin 에서 L2 정규화 후 cosine 비교)
"""

from __future__ import annotations

from pathlib import Path

DEST = (
    Path(__file__).resolve().parents[2]
    / "frontend" / "app" / "src" / "main" / "assets" / "object_embedder.tflite"
)


def export() -> Path:
    import tensorflow as tf  # 무거운 의존성 — 함수 안에서 import

    model = tf.keras.applications.MobileNetV3Small(
        input_shape=(224, 224, 3),
        include_top=False,
        weights="imagenet",
        pooling="avg",
        include_preprocessing=True,  # 입력이 0..255 그대로 — Kotlin 전처리 단순화
    )
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_types = [tf.float16]
    tflite_model = converter.convert()

    DEST.parent.mkdir(parents=True, exist_ok=True)
    DEST.write_bytes(tflite_model)
    print(f"저장 완료: {DEST} ({len(tflite_model) / 1024 / 1024:.1f}MB)")
    return DEST


if __name__ == "__main__":
    export()
