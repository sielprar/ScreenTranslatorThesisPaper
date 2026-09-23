from pathlib import Path


def test_recognizer_dataset_returns_tensor_pairs(tmp_path):
    from screentranslator_ml.data.synthetic import generate_recognizer_batch
    from screentranslator_ml.data.datasets import RecognizerDataset
    from screentranslator_ml.data.vocab import Vocab

    generate_recognizer_batch(
        out_dir=tmp_path / "batch",
        n=8,
        corpus=Path("data/corpora/ui_phrases_ru.txt"),
        fonts_dir=Path("data/fonts"),
        seed=0,
    )
    vocab = Vocab.from_file(Path("data/corpora/vocab_cyrillic.txt"))
    ds = RecognizerDataset([tmp_path / "batch"], vocab=vocab, image_height=32)
    assert len(ds) == 8
    img, ids = ds[0]
    import torch
    assert isinstance(img, torch.Tensor) and img.shape[0] == 1  # grayscale
    assert isinstance(ids, torch.Tensor) and ids.ndim == 1


def test_detector_dataset_returns_image_and_target_maps(tmp_path):
    from screentranslator_ml.data.synthetic import generate_detector_batch
    from screentranslator_ml.data.datasets import DetectorDataset

    generate_detector_batch(
        out_dir=tmp_path / "det",
        n=4,
        corpus=Path("data/corpora/ui_phrases_ru.txt"),
        fonts_dir=Path("data/fonts"),
        canvas_size=(640, 640),
        seed=0,
    )
    ds = DetectorDataset([tmp_path / "det"], input_size=640)
    assert len(ds) == 4
    img, prob, thresh, mask = ds[0]
    import torch
    assert img.shape == (3, 640, 640)
    assert prob.shape == (160, 160)
    assert thresh.shape == (160, 160)
    assert mask.shape == (160, 160)
