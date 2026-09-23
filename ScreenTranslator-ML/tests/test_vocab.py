from pathlib import Path
from screentranslator_ml.data.vocab import Vocab

VOCAB_FILE = Path("data/corpora/vocab_cyrillic.txt")


def test_vocab_loads_expected_size():
    v = Vocab.from_file(VOCAB_FILE)
    assert len(v) == 90, f"expected 90 classes incl. blank + space, got {len(v)}"


def test_vocab_blank_is_index_zero():
    v = Vocab.from_file(VOCAB_FILE)
    assert v.blank_id == 0


def test_vocab_roundtrip():
    v = Vocab.from_file(VOCAB_FILE)
    s = "Привет, мир!"
    ids = v.encode(s)
    decoded = v.decode(ids, remove_blanks=False)
    assert decoded == s


def test_vocab_rejects_out_of_alphabet():
    import pytest
    v = Vocab.from_file(VOCAB_FILE)
    with pytest.raises(KeyError):
        v.encode("Hello")  # Latin not in vocab


def test_vocab_decode_respects_remove_blanks_flag():
    v = Vocab.from_file(VOCAB_FILE)
    # 'А' is at index 2 in the vocab (after <blank> and space).
    a_idx = v.encode("А")[0]
    ids = [v.blank_id, a_idx, v.blank_id, a_idx]

    # Default: blanks stripped.
    assert v.decode(ids) == "АА"
    assert v.decode(ids, remove_blanks=True) == "АА"

    # Explicit False: blanks preserved as the literal "<blank>" marker.
    assert v.decode(ids, remove_blanks=False) == "<blank>А<blank>А"
