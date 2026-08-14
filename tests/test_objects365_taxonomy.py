import pytest

from ai.taxonomy import OBJECTS365_YOLO26


def test_objects365_taxonomy_is_complete_and_has_stable_known_ids():
    taxonomy = OBJECTS365_YOLO26

    assert taxonomy.taxonomy_id == "objects365_yolo26_objv1_365"
    assert taxonomy.class_count == 365
    assert len(set(taxonomy.labels)) == 365
    assert len(taxonomy.object_labels) == 364
    assert taxonomy.class_id_for_label("person") == 0
    assert taxonomy.class_id_for_label("chair") == 2
    assert taxonomy.class_id_for_label("bottle") == 5
    assert taxonomy.class_id_for_label("cup") == 7
    assert taxonomy.class_id_for_label("wine glass") == 27
    assert taxonomy.label_for_class_id(364) == "flashlight"


def test_objects365_taxonomy_preserves_model_spaces_and_slashes():
    taxonomy = OBJECTS365_YOLO26

    assert taxonomy.class_id_for_label("cell phone") == 77
    assert taxonomy.class_id_for_label("cabinet/shelf") == 6
    with pytest.raises(ValueError, match="Unknown"):
        taxonomy.class_id_for_label("cell_phone")


def test_objects365_taxonomy_rejects_reordered_or_incomplete_model_names():
    taxonomy = OBJECTS365_YOLO26
    correct_names = dict(enumerate(taxonomy.labels))
    taxonomy.validate_model_names(correct_names)

    wrong_names = dict(correct_names)
    wrong_names[5] = "cup"
    with pytest.raises(ValueError, match="class 5"):
        taxonomy.validate_model_names(wrong_names)

    with pytest.raises(ValueError, match="class 364"):
        taxonomy.validate_model_names(taxonomy.labels[:-1])


def test_objects365_taxonomy_only_accepts_supported_non_person_observations_as_objects():
    taxonomy = OBJECTS365_YOLO26

    assert taxonomy.is_supported_object(class_id=7, observed_label="ignored")
    assert taxonomy.is_supported_object(class_id=None, observed_label="Wine Glass")
    assert not taxonomy.is_supported_object(class_id=0, observed_label="bottle")
    assert not taxonomy.is_supported_object(class_id=None, observed_label="face")
    assert not taxonomy.is_supported_object(class_id=365, observed_label="cup")
