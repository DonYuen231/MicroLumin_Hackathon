import math
import sys

import pytest

from app.errors import ProblemError
from app.faces import Detection, FaceEngineUnavailable, ImageDecodeError, select_face
from tests.conftest import AUTH, face
from tests.test_auth_errors import assert_problem

W, H = 1000, 800


def det(*faces):
    return Detection(width=W, height=H, faces=list(faces))


def post(client):
    return client.post("/v1/faces/embed", headers=AUTH, files={"image": ("a.jpg", b"jpegbytes")})


def test_no_face():
    with pytest.raises(ProblemError) as exc:
        select_face(det())
    assert exc.value.status_code == 422
    assert exc.value.title == "no_face"


def test_low_confidence_faces_ignored():
    with pytest.raises(ProblemError) as exc:
        select_face(det(face([400, 300, 600, 500], score=0.2)))
    assert exc.value.title == "no_face"


def test_single_face_returned():
    f = face([10, 10, 60, 60])
    assert select_face(det(f)) is f


def test_two_similar_faces_is_multiple():
    with pytest.raises(ProblemError) as exc:
        select_face(det(face([300, 300, 450, 450]), face([550, 300, 700, 450])))
    assert exc.value.status_code == 422
    assert exc.value.title == "multiple_faces"


def test_largest_near_center_wins_when_clearly_bigger():
    centre = face([350, 250, 650, 550])  # 300x300, centred
    side = face([20, 20, 120, 120])  # 100x100
    assert select_face(det(side, centre)) is centre


def test_large_face_in_corner_is_not_the_subject():
    corner = face([0, 0, 300, 300])
    small_centre = face([470, 370, 530, 430])
    with pytest.raises(ProblemError) as exc:
        select_face(det(corner, small_centre))
    assert exc.value.title == "multiple_faces"


def test_central_face_not_clearly_bigger_is_multiple():
    a = face([350, 250, 650, 550])  # 90000
    b = face([100, 250, 380, 530])  # 78400, ratio 1.15; its centre is 240 left of centre
    with pytest.raises(ProblemError) as exc:
        select_face(det(a, b))
    assert exc.value.title == "multiple_faces"


def test_ratio_boundary():
    a = face([400, 300, 600, 500])  # 40000
    b = face([20, 20, 20 + 163, 20 + 163])  # 26569: ratio ~1.5056
    assert select_face(det(a, b)) is a
    c = face([20, 20, 20 + 170, 20 + 170])  # 28900: ratio ~1.38
    with pytest.raises(ProblemError):
        select_face(det(a, c))


def test_route_returns_single_normalized_face(client, engine):
    engine.detection = det(face([350, 250, 650, 550], score=0.98), face([5, 5, 50, 50]))
    resp = post(client)
    assert resp.status_code == 200
    faces = resp.json()["faces"]
    assert len(faces) == 1
    f = faces[0]
    assert f["bbox"] == [350, 250, 650, 550]
    assert f["det_score"] == 0.98
    assert len(f["embedding"]) == 512
    assert math.isclose(math.sqrt(sum(v * v for v in f["embedding"])), 1.0, rel_tol=1e-9)
    assert f["embedding"][:2] == pytest.approx([0.6, 0.8])


def test_route_no_face_problem(client):
    assert_problem(post(client), 422, "no_face")


def test_route_multiple_faces_problem(client, engine):
    engine.detection = det(face([300, 300, 450, 450]), face([550, 300, 700, 450]))
    assert_problem(post(client), 422, "multiple_faces")


def test_route_bad_image(client, engine):
    engine.error = ImageDecodeError("x")
    assert_problem(post(client), 422, "invalid_image")


def test_route_engine_unavailable(client, engine):
    engine.error = FaceEngineUnavailable("x")
    assert_problem(post(client), 503, "face_engine_unavailable")


def test_default_engine_degrades_cleanly_without_insightface(monkeypatch):
    import httpx
    from fastapi.testclient import TestClient

    from app.main import create_app
    from tests.conftest import make_settings

    monkeypatch.setitem(sys.modules, "insightface", None)  # forces ImportError
    monkeypatch.setitem(sys.modules, "insightface.app", None)
    app = create_app(make_settings(), http_client=httpx.AsyncClient())
    resp = post(TestClient(app, raise_server_exceptions=False))
    assert_problem(resp, 503, "face_engine_unavailable")
