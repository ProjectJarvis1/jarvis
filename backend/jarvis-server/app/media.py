"""Where a user's photos and recordings live, and the only way files are deleted.

Paths stored in record metadata can be edited through the API, so they are never trusted for
deletion: a file is removed only when it sits directly in the signed-in user's own folder."""
import shutil
from pathlib import Path

from .auth import ADMIN_USER, user_id


def user_dir(base: Path, user: str = "") -> Path:
    """Files saved before per-user sign-in stay in `base` and belong to the admin user."""
    user = user or user_id()
    return base if user == ADMIN_USER else base / "users" / user


def safe_unlink(raw: str, base: Path) -> None:
    try:
        path = Path(raw).resolve()
        if path.parent == user_dir(base).resolve() and path.is_file():
            path.unlink()
    except (OSError, RuntimeError, ValueError):
        pass


def remove_user_files(base: Path, user: str) -> None:
    if user != ADMIN_USER:
        shutil.rmtree(user_dir(base, user), ignore_errors=True)
