"""A person record is the source of truth; its person_profile memory is the searchable copy."""
from .embeddings import embed_text


def person_profile_text(person) -> str:
    aliases = [a for a in (person.get("aliases") or []) if a]
    return "\n".join(
        part
        for part in [
            f"사람: {person.get('name')}",
            f"별칭: {', '.join(aliases)}" if aliases else None,
            f"소속: {person.get('org')}" if person.get("org") else None,
            f"직책: {person.get('role')}" if person.get("role") else None,
            f"메모: {person.get('notes_summary')}" if person.get("notes_summary") else None,
        ]
        if part
    )


def sync_person_profile(cur, person) -> None:
    """After a correction, search must not keep returning the old name, company or note."""
    text = person_profile_text(person)
    cur.execute(
        """UPDATE memories SET text=%s, embedding=%s
           WHERE source='derived' AND metadata->>'memory_type'='person_profile'
             AND metadata->>'origin_person_id'=%s""",
        (text, embed_text(text), str(person["id"])),
    )
