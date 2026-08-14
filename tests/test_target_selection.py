import pytest

from ai.on_device_cv.contracts import BoundingBox, FrameResult, TrackedObject
from ai.on_device_cv.target_selection import (
    TargetCountStatus,
    TargetSelectionState,
    TargetSelector,
    TargetSelectorConfig,
)
from ai.target_spec import (
    Framing,
    SubjectType,
    TargetSpec,
    TargetSpecSource,
    TargetSpecStatus,
)
from ai.taxonomy import ObjectTaxonomy

BOX = BoundingBox(0.1, 0.1, 0.4, 0.8)
PERSON_1 = TrackedObject(1, "Person", 0.9, BOX, class_id=0)
PERSON_2 = TrackedObject(2, "Person", 0.8, BOX, class_id=0)
BOTTLE = TrackedObject(3, "Bottle", 0.85, BOX, class_id=5)
CHAIR = TrackedObject(4, "Chair", 0.75, BOX, class_id=2)
ALL_OBJECTS = FrameResult((PERSON_1, PERSON_2, BOTTLE, CHAIR))


def target_spec(
    subject_type=SubjectType.PERSON,
    *,
    subject_count=None,
    status=TargetSpecStatus.OK,
    schema_version="0.1",
    object_label=None,
):
    return TargetSpec(
        session_id="session-1",
        raw_text="테스트 발화",
        source=TargetSpecSource.ONDEVICE,
        schema_version=schema_version,
        status=status,
        subject_type=subject_type,
        object_label=object_label,
        subject_count=subject_count,
        framing=Framing.FULL_BODY,
        confidence=0.9,
    )


def test_person_intent_selects_people_after_all_objects_were_tracked():
    selection = TargetSelector().select(ALL_OBJECTS, target_spec())

    assert selection.state is TargetSelectionState.SELECTED
    assert [item.track_id for item in selection.candidates] == [1, 2]
    assert selection.to_frame_result().to_dict()["objects"][0]["label"] == "Person"
    # Selection does not mutate or remove the original all-object tracking result.
    assert [item.track_id for item in ALL_OBJECTS.objects] == [1, 2, 3, 4]


def test_generic_object_intent_selects_all_supported_non_person_objects():
    selection = TargetSelector().select(ALL_OBJECTS, target_spec(SubjectType.OBJECT))

    assert [item.track_id for item in selection.candidates] == [3, 4]


def test_specific_object_label_selects_only_that_objects365_class():
    cup = TrackedObject(10, "cup", 0.9, BOX, class_id=7)
    selection = TargetSelector().select(
        FrameResult((PERSON_1, BOTTLE, CHAIR, cup)),
        target_spec(
            SubjectType.OBJECT,
            schema_version="0.2",
            object_label="cup",
        ),
    )

    assert [item.track_id for item in selection.candidates] == [10]


def test_specific_object_label_uses_model_label_when_class_id_is_unavailable():
    wine_glass = TrackedObject(10, "Wine Glass", 0.9, BOX)
    selection = TargetSelector().select(
        FrameResult((wine_glass, BOTTLE)),
        target_spec(
            SubjectType.OBJECT,
            schema_version="0.2",
            object_label="wine glass",
        ),
    )

    assert [item.track_id for item in selection.candidates] == [10]


def test_specific_object_missing_from_frame_reports_searching_after_filtering():
    selection = TargetSelector().select(
        ALL_OBJECTS,
        target_spec(
            SubjectType.OBJECT,
            schema_version="0.2",
            object_label="cup",
            subject_count=1,
        ),
    )

    assert selection.state is TargetSelectionState.SEARCHING
    assert selection.count_status is TargetCountStatus.UNDER
    assert selection.candidates == ()


def test_generic_object_intent_excludes_unknown_extension_classes():
    face = TrackedObject(10, "face", 0.9, BOX)

    selection = TargetSelector().select(
        FrameResult((BOTTLE, face)),
        target_spec(SubjectType.OBJECT),
    )

    assert [item.track_id for item in selection.candidates] == [3]


def test_selector_accepts_an_explicit_alternative_taxonomy_mapping():
    taxonomy = ObjectTaxonomy(
        taxonomy_id="test_taxonomy",
        model_id="test.pt",
        model_sha256="0" * 64,
        labels=("bottle", "person"),
    )
    selector = TargetSelector(TargetSelectorConfig(taxonomy=taxonomy))
    custom_person = TrackedObject(10, "human", 0.9, BOX, class_id=1)

    selection = selector.select(FrameResult((custom_person,)), target_spec())

    assert [item.track_id for item in selection.candidates] == [10]


def test_legacy_person_class_override_remains_supported():
    selector = TargetSelector(TargetSelectorConfig(person_class_id=7))
    custom_person = TrackedObject(10, "human", 0.9, BOX, class_id=7)

    selection = selector.select(FrameResult((custom_person,)), target_spec())

    assert [item.track_id for item in selection.candidates] == [10]


def test_reduced_taxonomy_reports_searching_for_an_unsupported_requested_label():
    taxonomy = ObjectTaxonomy(
        taxonomy_id="reduced_taxonomy",
        model_id="reduced.pt",
        model_sha256="0" * 64,
        labels=("person", "cup"),
    )
    selector = TargetSelector(TargetSelectorConfig(taxonomy=taxonomy))
    selection = selector.select(
        FrameResult((TrackedObject(10, "cup", 0.9, BOX, class_id=1),)),
        target_spec(
            SubjectType.OBJECT,
            schema_version="0.2",
            object_label="bottle",
            subject_count=1,
        ),
    )

    assert selection.state is TargetSelectionState.SEARCHING
    assert selection.candidates == ()


def test_landscape_intent_does_not_fabricate_an_object_target():
    selection = TargetSelector().select(ALL_OBJECTS, target_spec(SubjectType.LANDSCAPE))

    assert selection.state is TargetSelectionState.SCENE_ONLY
    assert selection.candidates == ()
    assert selection.count_status is TargetCountStatus.NOT_APPLICABLE


@pytest.mark.parametrize(
    ("requested_count", "expected_state", "expected_count_status"),
    [
        (1, TargetSelectionState.AMBIGUOUS, TargetCountStatus.OVER),
        (2, TargetSelectionState.SELECTED, TargetCountStatus.EXACT),
        (3, TargetSelectionState.SEARCHING, TargetCountStatus.UNDER),
    ],
)
def test_subject_count_reports_over_exact_and_under_without_arbitrary_truncation(
    requested_count,
    expected_state,
    expected_count_status,
):
    selection = TargetSelector().select(
        ALL_OBJECTS,
        target_spec(subject_count=requested_count),
    )

    assert selection.state is expected_state
    assert selection.count_status is expected_count_status
    assert [item.track_id for item in selection.candidates] == [1, 2]


def test_unresolved_nlu_result_does_not_choose_a_target():
    selection = TargetSelector().select(
        ALL_OBJECTS,
        target_spec(status=TargetSpecStatus.NEEDS_CLARIFICATION),
    )

    assert selection.state is TargetSelectionState.UNRESOLVED
    assert selection.candidates == ()


def test_changing_intent_reuses_the_existing_track_ids():
    selector = TargetSelector()

    people = selector.select(ALL_OBJECTS, target_spec(SubjectType.PERSON))
    objects = selector.select(ALL_OBJECTS, target_spec(SubjectType.OBJECT))

    assert [item.track_id for item in people.candidates] == [1, 2]
    assert [item.track_id for item in objects.candidates] == [3, 4]


def test_v01_selection_contract_keeps_existing_shape_and_object_schema():
    selection = TargetSelector().select(ALL_OBJECTS, target_spec(subject_count=2))

    payload = selection.to_dict()
    assert payload["schemaVersion"] == "0.1"
    assert payload["sessionId"] == "session-1"
    assert payload["state"] == "selected"
    assert payload["detectedCount"] == 2
    assert payload["countStatus"] == "exact"
    assert "objectLabel" not in payload
    assert set(payload["objects"][0]) == {"track_id", "label", "confidence", "bbox"}


def test_v02_selection_contract_reports_requested_object_label():
    selection = TargetSelector().select(
        ALL_OBJECTS,
        target_spec(
            SubjectType.OBJECT,
            schema_version="0.2",
            object_label="bottle",
            subject_count=1,
        ),
    )

    payload = selection.to_dict()
    assert payload["schemaVersion"] == "0.2"
    assert payload["objectLabel"] == "bottle"
    assert payload["state"] == "selected"
    assert [item["track_id"] for item in payload["objects"]] == [3]
