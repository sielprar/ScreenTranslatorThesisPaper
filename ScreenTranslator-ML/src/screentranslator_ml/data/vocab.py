from __future__ import annotations
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Vocab:
    """Character vocabulary for a CTC OCR recognizer.

    Loaded from a plain-text file (one character per line, `<blank>` sentinel
    on the first line, a single literal space on the second). Indices are
    load-bearing — they define the CTC output-layer class layout — so the
    file's line order must not change once training starts.
    """

    chars: list[str]
    _char_to_id: dict[str, int] = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        # Build the encode-side lookup once; training-loop calls encode() per
        # sample, so rebuilding this dict on every call is real waste.
        self._char_to_id = {c: i for i, c in enumerate(self.chars)}

    @classmethod
    def from_file(cls, path: Path) -> "Vocab":
        chars = Path(path).read_text(encoding="utf-8").splitlines()
        return cls(chars=chars)

    @property
    def blank_id(self) -> int:
        return self.chars.index("<blank>")

    def __len__(self) -> int:
        return len(self.chars)

    def encode(self, s: str) -> list[int]:
        return [self._char_to_id[c] for c in s]

    def decode(self, ids: list[int], remove_blanks: bool = True) -> str:
        """Convert class-id list to a string.

        Assumes the input is a list of *already-collapsed* class ids — this
        method does NOT do CTC's repeat-collapse step; that lives in
        `train/ctc_utils.py::ctc_greedy_decode`. Here we only handle the
        `<blank>` sentinel: strip it (default) or render it as the literal
        `<blank>` marker for debug output when `remove_blanks=False`.
        """
        out: list[str] = []
        for i in ids:
            c = self.chars[i]
            if c == "<blank>":
                if remove_blanks:
                    continue
                out.append("<blank>")
                continue
            out.append(c)
        return "".join(out)
