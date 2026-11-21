import json

from app.profiles import load_refresh_fixture
from tests.conftest import AUTH
from tests.test_auth_errors import assert_problem


def refresh(client, name, **extra):
    body = {"person_id": "p_1", "name": name, "shared_links": [], "profile": {"name": name}}
    body.update(extra)
    return client.post("/v1/profiles/refresh", headers=AUTH, json=body)


def test_refresh_reads_fixture(client):
    resp = refresh(client, "Sam Rivera")
    assert resp.status_code == 200
    updates = resp.json()["since_you_met"]
    assert updates
    assert all(isinstance(u, str) and len(u) <= 60 for u in updates)


def test_refresh_name_is_case_and_space_insensitive(client):
    assert refresh(client, "  sam RIVERA ").json() == refresh(client, "Sam Rivera").json()


def test_refresh_unknown_name_is_empty(client):
    assert refresh(client, "Nobody Known").json() == {"since_you_met": []}


def test_refresh_without_profile(client):
    resp = client.post(
        "/v1/profiles/refresh", headers=AUTH, json={"person_id": "p", "name": "Sam Rivera"}
    )
    assert resp.status_code == 200


def test_refresh_validation_problem(client):
    resp = client.post("/v1/profiles/refresh", headers=AUTH, json={"name": "Sam"})
    assert_problem(resp, 422, "validation_error")


def test_refresh_uses_configured_fixture(make_client, tmp_path):
    path = tmp_path / "r.json"
    path.write_text(json.dumps({"Zed": ["Updated thing"]}), encoding="utf-8")
    client = make_client(refresh_fixture_path=path)
    assert refresh(client, "zed").json() == {"since_you_met": ["Updated thing"]}


def test_missing_fixture_is_empty(tmp_path):
    assert load_refresh_fixture(tmp_path / "missing.json") == {}


def test_bad_fixture_is_empty(tmp_path):
    path = tmp_path / "bad.json"
    path.write_text("{nope", encoding="utf-8")
    assert load_refresh_fixture(path) == {}
