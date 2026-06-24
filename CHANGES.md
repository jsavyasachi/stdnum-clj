# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project adheres to
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-06-23

Initial release.

### Added
- `stdnum.core` - a unified facade for validating, parsing, and formatting standard
  identifier numbers, dispatched on a type keyword:
  - `valid?`, `parse`, `format`, `compact`, `detect`, `card-network`, and the `types` set.
  - Identifier types: `:credit-card` (with network detection), `:iban`, `:bic`, `:isbn`,
    `:issn`, `:isin`, `:aba` (US bank routing), `:imei`, `:luhn`.
- Idiomatic facade over Apache Commons Validator 1.10.1 and iban4j 3.2.11 - no algorithm
  reimplementation. Bad input data never throws; only an unknown identifier type does.

[0.1.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.1.0
