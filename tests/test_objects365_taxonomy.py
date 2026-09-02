import pytest

from ai.taxonomy import OBJECTS365_YOLO26


def test_taxonomy_is_complete_and_has_stable_known_ids():
    taxonomy = OBJECTS365_YOLO26

    assert taxonomy.taxonomy_id == "snapsight_kr170_v1"
    assert taxonomy.class_count == 170
    assert len(set(taxonomy.labels)) == 170
    assert len(taxonomy.object_labels) == 169
    # person 은 어느 taxonomy 에서든 0 이어야 한다 — 얼굴/인물 경로가 이 값을 전제한다.
    assert taxonomy.class_id_for_label("person") == 0
    assert taxonomy.class_id_for_label("cup") == 24
    assert taxonomy.class_id_for_label("wine glass") == 25
    assert taxonomy.class_id_for_label("bottle") == 26
    assert taxonomy.class_id_for_label("chair") == 36
    assert taxonomy.label_for_class_id(169) == "corn"


def test_taxonomy_preserves_model_spaces_and_slashes():
    taxonomy = OBJECTS365_YOLO26

    assert taxonomy.class_id_for_label("cell phone") == 43
    assert taxonomy.class_id_for_label("cabinet/shelf") == 40
    with pytest.raises(ValueError, match="Unknown"):
        taxonomy.class_id_for_label("cell_phone")


def test_taxonomy_rejects_reordered_or_incomplete_model_names():
    taxonomy = OBJECTS365_YOLO26
    correct_names = dict(enumerate(taxonomy.labels))
    taxonomy.validate_model_names(correct_names)

    wrong_names = dict(correct_names)
    wrong_names[26] = "cup"          # 26 은 bottle 이어야 한다
    with pytest.raises(ValueError, match="class 26"):
        taxonomy.validate_model_names(wrong_names)

    with pytest.raises(ValueError, match="class 169"):
        taxonomy.validate_model_names(taxonomy.labels[:-1])


def test_taxonomy_only_accepts_supported_non_person_observations_as_objects():
    taxonomy = OBJECTS365_YOLO26

    assert taxonomy.is_supported_object(class_id=24, observed_label="ignored")
    assert taxonomy.is_supported_object(class_id=None, observed_label="Wine Glass")
    assert not taxonomy.is_supported_object(class_id=0, observed_label="bottle")
    assert not taxonomy.is_supported_object(class_id=None, observed_label="face")
    assert not taxonomy.is_supported_object(class_id=170, observed_label="cup")
