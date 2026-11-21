import pytest

from app.llm import ModelOutputError, extract_json


@pytest.mark.parametrize(
    "text",
    [
        '{"a": 1}',
        '  {"a": 1}\n',
        '```json\n{"a": 1}\n```',
        '```\n{"a": 1}\n```',
        '<think>reasoning {"a": 2}</think>{"a": 1}',
        '<think>\nmulti\nline\n</think>\n```json\n{"a": 1}\n```',
        'reasoning cut off</think>{"a": 1}',
        'Here you go: {"a": 1} hope that helps',
        '<THINK>x</THINK>{"a": 1}',
    ],
)
def test_extract_json(text):
    assert extract_json(text) == {"a": 1}


@pytest.mark.parametrize("text", ["", "not json", "<think>only thinking</think>", "{broken"])
def test_extract_json_failure(text):
    with pytest.raises(ModelOutputError):
        extract_json(text)
