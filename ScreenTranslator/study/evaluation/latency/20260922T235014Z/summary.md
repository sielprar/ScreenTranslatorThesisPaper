# Latency run

Build `803eb99`, emulator (Pixel 7 profile, Android 14, Apple Silicon host), warm models unless the scenario is `cold`. Times in ms, nearest-rank percentiles.

| Scenario | Case | Path | n | Total p50 | Total p95 | Max | Harvest p50 | OCR p50 | LID+NMT p50 | Overlay p50 | Cards (median) |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| first_view | menu_fr | ocr_full | 20 | 1789 | 2087 | 2448 | 34 | 634 | 962 | 2 | 15 |
| first_view | menu_ru | ocr_full | 20 | 5110 | 5523 | 5735 | 45 | 851 | 4019 | 2 | 77 |
| first_view | menu_de | ocr_full | 20 | 1296 | 1512 | 1561 | 7 | 455 | 664 | 2 | 1 |
| cached | menu_fr | hash_hit | 30 | 180 | 355 | 379 | 30 | 0 | 0 | 1 | 12 |
| first_view | browser_ru | a11y_full | 10 | 1059 | 2202 | 2202 | 219 | 0 | 300 | 59 | 25 |
| cached | browser_ru | a11y_hit | 30 | 18 | 41 | 182 | 17 | 0 | 0 | 1 | 25 |
| cold | menu_fr | ocr_full | 5 | 2828 | 3608 | 3608 | 71 | 1084 | 1421 | 49 | 12 |
| first_view | settings_ru | a11y_full | 20 | 375 | 542 | 851 | 11 | 0 | 142 | 27 | 15 |
| cached | settings_ru | a11y_hit | 30 | 6 | 12 | 14 | 5 | 0 | 0 | 1 | 15 |
| cold | settings_ru | a11y_full | 5 | 1442 | 1601 | 1601 | 274 | 0 | 788 | 21 | 15 |

Cold process start, service connected → first cards shown:

| Case | n | p50 | Max |
|---|---:|---:|---:|
| menu_fr | 5 | 2881 | 3687 |
| settings_ru | 5 | 1522 | 1696 |
