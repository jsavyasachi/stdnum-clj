# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.7.0] - 2026-06-24

Coverage expansion: 71 -> 90 identifier types. Adds LatAm tax IDs (Nubank/MercadoLibre audience),
more EU national/person IDs, research IDs (ORCID/ISNI), and the longer GS1 keys. Every new checksum
is clean-room from the public standard (or engine-backed) and verified against a real published
number.

### Added
- More commerce / industry identifiers: `:ean8` (EAN-8/GTIN-8), `:ismn` (sheet-music ISMN),
  `:cas` (CAS Registry Number for chemicals), `:imo` (IMO ship number). Clean-room / engine-backed,
  each verified against a real published number.
- National tax / person IDs: `:fr-nir` (France social security), `:pl-pesel` (Poland), `:ar-cuit`
  (Argentina), `:cl-rut` (Chile), `:co-nit` (Colombia). Clean-room, each verified against a real
  published number.
- More national IDs: `:pe-ruc` (Peru), `:ie-pps` (Ireland PPS), `:ee-ik` (Estonia isikukood),
  `:jmbg` (shared ex-Yugoslav number: RS/BA/ME/MK/SI/HR), `:ec-ced` (Ecuador cedula). Clean-room,
  each verified against a real published number.
- `:bg-egn` (Bulgaria), `:orcid` and `:isni` (researcher / name IDs, ISO 7064 MOD 11-2), and the
  longer GS1 keys `:gtin14` and `:sscc`. Clean-room, each verified against a real published number.

## [0.6.0] - 2026-06-24

Coverage expansion: 54 -> 71 identifier types, adding a commerce/vehicle/health category and
APAC business IDs. Every new checksum is clean-room from the public standard (or engine-backed)
and verified against a real published number.

### Added
- `:nz-ird` (New Zealand IRD), `:be-nn` (Belgium national number), `:fi-hetu` (Finland HETU).
  Clean-room, verified against published numbers.
- `:figi` (Financial Instrument Global Identifier, OMG check digit) and four more EU VAT numbers:
  `:mt-vat` (Malta), `:sk-vat` (Slovakia), `:lt-vat` (Lithuania), `:cy-vat` (Cyprus). Clean-room,
  each verified against a real published number.
- `:ro-vat` (Romania CUI), `:sg-nric` (Singapore NRIC/FIN, S/T/F/G series), `:hk-id` (Hong Kong
  HKID), `:kr-brn` (South Korea Business Registration Number). Clean-room, each verified against a
  real published number.
- Commerce / vehicle / healthcare: `:ean13` (EAN-13/GTIN-13 barcode), `:upc` (UPC-A), `:vin` (ISO
  3779 vehicle ID), `:nhs` (UK NHS number), `:npi` (US National Provider Identifier). Barcode checks
  are engine-backed (Commons Validator EAN-13); VIN/NHS/NPI are clean-room, each verified against a
  real published number.

## [0.5.0] - 2026-06-24

Coverage expansion: 40 -> 54 identifier types, spanning ~24 countries. Every checksum clean-room
from the public standard and verified against published example numbers.

### Added
- More VAT/GST: `:gr-vat` `:lu-vat` `:si-vat` `:ee-vat` `:hu-vat` `:in-gstin` (India GST, base-36
  check char).
- More tax & national IDs: `:pt-nif` `:cz-ico` `:jp-cn` `:au-tfn` `:hr-oib` (Croatia OIB),
  `:it-cf` (Italy codice fiscale, mod-26 check letter), `:ch-uid` `:ch-ahv` (Switzerland).

## [0.4.0] - 2026-06-23

Big coverage expansion: 13 -> 40 identifier types. Every new checksum is clean-room from the
public standard and verified against published example numbers (no third-party port; stays EPL).

### Added
- VAT numbers, country prefix optional on input: `:de-vat` `:fr-vat` `:it-vat` `:be-vat`
  `:pl-vat` `:gb-vat` `:at-vat` `:dk-vat` `:fi-vat` `:se-vat`.
- National IDs: `:gb-nino`, `:ca-sin`, `:au-abn`, `:in-pan`, `:in-aadhaar`, `:es-dni`, `:es-nie`,
  `:nl-bsn`, `:cn-ric`, `:se-pnr`, `:za-id`, `:no-org`, `:tr-tc`.
- Banking: `:mx-clabe` (Mexico CLABE bank account).

### Changed
- README: trimmed Usage to the core API and grouped the identifier types by category in a
  collapsible block, so the page stays scannable as the set grows.

## [0.3.0] - 2026-06-23

### Added
- Securities identifiers (engine-backed check digits): `:cusip` (US/Canada), `:sedol` (UK/Ireland) -
  rounding out the `:isin`/`:lei` set.
- US national numbers, clean-room from the public structural rules: `:us-ssn` (area/group/serial
  rules + the SSA's reserved advertising numbers, with `format`) and `:us-ein` (IRS campus prefix
  validation, with `format`).

## [0.2.0] - 2026-06-23

### Added
- Three new identifier types, implemented clean-room from their public standards (no
  third-party port; stays EPL):
  - `:lei` - Legal Entity Identifier (ISO 17442, ISO 7064 mod-97-10).
  - `:br-cpf` - Brazil individual taxpayer registry, with `format`.
  - `:br-cnpj` - Brazil company registry, with `format`.
- `compact`/`format` now also strip `/` (used by the CNPJ written form).

Country-specific identifiers are keyed by an ISO-3166 prefix (e.g. `:br-cpf`).

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

[0.7.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.7.0
[0.6.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.6.0
[0.5.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.5.0
[0.4.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.4.0
[0.3.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.3.0
[0.2.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.2.0
[0.1.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.1.0
