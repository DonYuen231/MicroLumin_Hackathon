"""Face detection/embedding behind a small interface, plus the selection rules."""

import math
import threading
from dataclasses import dataclass
from typing import Protocol

from pydantic import BaseModel

from app.config import Settings
from app.errors import ProblemError

MIN_DET_SCORE = 0.5
# "Near center": the face's center lies inside the middle 50% of the image in both axes.
CENTER_FRACTION = 0.25
# The chosen face must have at least this many times the area of the next largest face.
DOMINANCE_RATIO = 1.5


@dataclass(frozen=True)
class DetectedFace:
    bbox: tuple[float, float, float, float]
    det_score: float
    embedding: list[float]


@dataclass(frozen=True)
class Detection:
    width: int
    height: int
    faces: list[DetectedFace]


class FaceEngine(Protocol):
    def detect(self, image_bytes: bytes) -> Detection:
        """Detect faces and embed each. Raises ImageDecodeError for undecodable images."""
        ...


class ImageDecodeError(Exception):
    pass


class FaceEngineUnavailable(Exception):
    pass


class FaceOut(BaseModel):
    bbox: list[float]
    det_score: float
    embedding: list[float]


class EmbedResponse(BaseModel):
    faces: list[FaceOut]


def _area(face: DetectedFace) -> float:
    x1, y1, x2, y2 = face.bbox
    return max(0.0, x2 - x1) * max(0.0, y2 - y1)


def _near_center(face: DetectedFace, width: int, height: int) -> bool:
    x1, y1, x2, y2 = face.bbox
    cx, cy = (x1 + x2) / 2, (y1 + y2) / 2
    return (
        abs(cx - width / 2) <= CENTER_FRACTION * width
        and abs(cy - height / 2) <= CENTER_FRACTION * height
    )


def l2_normalize(vec: list[float]) -> list[float]:
    norm = math.sqrt(sum(v * v for v in vec))
    if norm == 0:
        return list(vec)
    return [v / norm for v in vec]


def select_face(detection: Detection) -> DetectedFace:
    """Pick exactly one face or raise a 422 problem (no_face / multiple_faces)."""
    faces = [f for f in detection.faces if f.det_score >= MIN_DET_SCORE]
    if not faces:
        raise ProblemError(422, "no_face", "No face was found in the image.")
    if len(faces) == 1:
        return faces[0]
    ranked = sorted(faces, key=_area, reverse=True)
    central = [f for f in ranked if _near_center(f, detection.width, detection.height)]
    if central:
        best = central[0]
        others = [f for f in ranked if f is not best]
        runner_up = max(_area(f) for f in others)
        if _area(best) >= DOMINANCE_RATIO * runner_up:
            return best
    raise ProblemError(
        422, "multiple_faces", "Several faces were found and none is clearly the subject."
    )


def embed_response(detection: Detection) -> EmbedResponse:
    face = select_face(detection)
    return EmbedResponse(
        faces=[
            FaceOut(
                bbox=[float(v) for v in face.bbox],
                det_score=float(face.det_score),
                embedding=l2_normalize(face.embedding),
            )
        ]
    )


class InsightFaceEngine:
    """InsightFace buffalo_l on CPU. Imported lazily so the app runs without it installed."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._app = None
        self._load_lock = threading.Lock()
        self._run_lock = threading.Lock()

    def load(self) -> None:
        with self._load_lock:
            if self._app is not None:
                return
            try:
                from insightface.app import FaceAnalysis  # noqa: PLC0415
            except ImportError as exc:
                raise FaceEngineUnavailable(
                    "InsightFace is not installed (it is only in the Docker image)."
                ) from exc
            fa = FaceAnalysis(
                name=self._settings.face_model,
                root=self._settings.face_model_root,
                allowed_modules=["detection", "recognition"],
                providers=["CPUExecutionProvider"],
            )
            fa.prepare(ctx_id=-1, det_size=(640, 640))
            self._app = fa

    def detect(self, image_bytes: bytes) -> Detection:
        self.load()
        import cv2  # noqa: PLC0415
        import numpy as np  # noqa: PLC0415

        img = cv2.imdecode(np.frombuffer(image_bytes, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise ImageDecodeError("Could not decode the image.")
        height, width = img.shape[:2]
        with self._run_lock:
            found = self._app.get(img)  # type: ignore[union-attr]
        faces = [
            DetectedFace(
                bbox=tuple(float(v) for v in f.bbox),  # type: ignore[arg-type]
                det_score=float(f.det_score),
                embedding=[float(v) for v in f.normed_embedding],
            )
            for f in found
        ]
        return Detection(width=width, height=height, faces=faces)
