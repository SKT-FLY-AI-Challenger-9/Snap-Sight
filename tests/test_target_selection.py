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

BOX = BoundingBox(0.1, 0.1, 0.4, 0.8)
PERSON_1 = TrackedObject(1, "Person", 0.9, BOX, class_id=0)
PERSON_2 = TrackedObject(2, "Person", 0.8, BOX, class_id=0)
BOTTLE = TrackedObject(3, "Bottle", 0.85, BOX, class_id=8)
CHAIR = TrackedObject(4, "Chair", 0.75, BOX, class_id=2)
ALL_OBJECTS = FrameResult((PERSON_1, PERSON_2, BOTTLE, CHAIR))


def target_spec(
    subject_type=SubjectType.PERSON,
    *,
    subject_count=None,
    status=TargetSpecStatus.OK,
):
    return TargetSpec(
        session_id="session-1",
        raw_text="테스트 발화",
        source=TargetSpecSource.ONDEVICE,
        status=status,
        subject_type=subject_type,
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


def test_generic_object_intent_selects_all_non_person_objects():
    selection = TargetSelector().select(ALL_OBJECTS, target_spec(SubjectType.OBJECT))

    assert [item.track_id for item in selection.candidates] == [3, 4]


def test_default_person_mapping_uses_label_instead_of_assuming_class_zero():
    custom_person = TrackedObject(10, "Person", 0.9, BOX, class_id=7)
    class_zero_bottle = TrackedObject(11, "Bottle", 0.8, BOX, class_id=0)

    selection = TargetSelector().select(
        FrameResult((custom_person, class_zero_bottle)),
        target_spec(),
    )

    assert [item.track_id for item in selection.candidates] == [10]


def test_explicit_taxonomy_mapping_can_select_a_nonzero_person_class_id():
    selector = TargetSelector(TargetSelectorConfig(person_class_id=7))
    custom_person = TrackedObject(10, "human", 0.9, BOX, class_id=7)
    class_zero_person_label = TrackedObject(11, "Person", 0.8, BOX, class_id=0)

    selection = selector.select(
        FrameResult((custom_person, class_zero_person_label)),
        target_spec(),
    )

    assert [item.track_id for item in selection.candidates] == [10]


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


def test_selection_contract_includes_count_state_and_existing_object_schema():
    selection = TargetSelector().select(ALL_OBJECTS, target_spec(subject_count=2))

    payload = selection.to_dict()
    assert payload["schemaVersion"] == "0.1"
    assert payload["sessionId"] == "session-1"
    assert payload["state"] == "selected"
    assert payload["detectedCount"] == 2
    assert payload["countStatus"] == "exact"
    assert set(payload["objects"][0]) == {"track_id", "label", "confidence", "bbox"}
