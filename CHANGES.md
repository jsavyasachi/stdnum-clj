# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.11.0] - 2026-06-24

Two major capability additions plus broader format coverage and two new VAT types (92 -> 94). The
two real gaps versus python-stdnum - standalone check-digit algorithms and online VAT validation -
are now closed.

### Added
- More canonical `format`: `:hk-id` (`A123456(3)`), `:kr-brn` (`124-81-00998`), `:au-abn`
  (`51 824 753 556`), `:au-tfn` (`123 456 782`), `:no-org` (`974 760 673`).
- New namespace `stdnum.checkdigit` exposing the underlying algorithms as standalone primitives
  (the python-stdnum `stdnum.luhn`/`verhoeff`/`iso7064` parallel): `luhn-valid?` /
  `luhn-check-digit`, `verhoeff-valid?` / `verhoeff-check-digit`, `iso7064-mod11-2-valid?` /
  `iso7064-mod11-2-check`, and `iso7064-mod97-10-valid?`.
- Two more VAT types (90 -> ... 94 total): `:es-vat` (Spain - covers the DNI, NIE and CIF forms;
  CIF accepts the digit- or letter-control convention) and `:ie-vat` (Ireland, mod-23 check letter).
  Verified against Telefónica's CIF and Google Ireland's VAT.
- New namespace `stdnum.vies` for **online** EU VAT validation against the official VIES service
  (`vies/check`), confirming a VAT number actually exists in the member-state registry and returning
  the trader name/address where disclosed. Member-state errors (e.g. `MS_UNAVAILABLE`) surface as
  `{:error ...}` so a transient outage is never mistaken for an invalid number. Requires JDK 11+
  (uses `java.net.http`); adds an `org.clojure/data.json` dependency. `stdnum.core` remains pure and
  network-free.

## [0.10.0] - 2026-06-24

Depth pass, part 2: broader `format` coverage and `parse` decomposition of structural identifiers
(BIC, CLABE, IMEI, ISIN) and non-date IDs. Still 92 types; this release deepens the surface so most
identifiers now report their parts, not just `{:valid? true}`. 22 types have `parse` extraction and
22 have canonical `format`.

### Added
- Canonical `format` for more existing types with standard display forms: `:nhs` (`943 476 5919`),
  `:gb-nino` (`AB 12 34 56 C`), `:in-aadhaar` (`2341 2341 2346`), `:ch-ahv` (`756.9217.0769.85`),
  `:ca-sin` (`046 454 286`), `:fr-nir` (`2 55 08 14 168 025 38`).
- More non-date `parse` extraction: `:in-pan` -> `:holder-type` (from the 4th character), `:ec-ced`
  -> `:province-code` `:province`, `:pe-ruc` -> `:entity-type` (`:company`/`:natural-person`), and
  `:credit-card` now also returns `:iin` (first 6) and `:last4`.
- Structural `parse` decomposition: `:bic` -> `:bank-code` `:country` `:location-code`
  `:branch-code` (via iban4j); `:mx-clabe` -> `:bank-code` `:branch-code` `:account`; `:imei` ->
  `:tac` `:serial`; `:isin` -> `:country` `:nsin`.

## [0.9.0] - 2026-06-24

Depth pass continued: more `parse` field-extraction and canonical `format`. Extraction now covers
every shipped identifier that embeds a birth date. No new types (still 92); this release deepens
the existing surface.

### Added
- `parse :cn-ric` (China resident ID) now extracts `:birth-date` (full YYYY-MM-DD) and `:gender`.
- More `parse` extraction: `:pl-pesel` -> `:birth-date` (century resolved from the PESEL month
  offset, so unambiguous) + `:gender`; `:fr-nir` -> `:gender` `:birth-year` `:birth-month`
  `:department`; `:se-pnr` -> `:birth-date` `:gender`; `:be-nn` -> `:birth-date` (century from the
  validating mod-97 base) + `:gender`; `:bg-egn` -> `:birth-date` (century from month offset) +
  `:gender`.
- Canonical `format` for `:se-pnr` (`811218-9876`) and `:be-nn` (`00.01.25-111.48`).
- Canonical `format` for more types: `:orcid` (`0000-0002-1825-0097`), `:isni`
  (`0000 0001 2103 2683`), `:cas` (`7732-18-5`), `:ar-cuit` (`30-70308853-4`), `:cl-rut`
  (`97.004.000-5`), `:co-nit` (`890.903.938-8`).

## [0.8.0] - 2026-06-24

Two new types (90 -> 92) plus a depth pass: `parse` now extracts the structured data embedded in
several identifiers, matching the most-used part of python-stdnum's per-type richness. Everything
verified against published example numbers; stays EPL.

### Added
- `:gln` (GS1 Global Location Number) and `:mx-curp` (Mexico CURP). Clean-room / engine-backed,
  each verified against a published example.
- `parse` now extracts the structured data embedded in several IDs (not just `{:valid? true}`):
  - `:mx-curp` -> `:birth-date` `:gender` `:state` `:state-name`
  - `:ee-ik` (Estonia), `:jmbg` (ex-Yugoslav) -> `:birth-date` `:gender` (+ `:region` for JMBG)
  - `:za-id` (South Africa) -> `:gender` `:citizen` `:birth-date`
  - `:vin` -> `:wmi` `:vds` `:vis` `:model-year` `:plant` `:serial`
  - `:it-cf` (Italy codice fiscale) -> `:gender` `:birth-day` `:birth-month` `:birth-year`
    `:comune-code` (the century is not encoded in a CF, so the 2-digit year is returned as-is)
  - `:iban` -> now also `:bank-code` `:branch-code` `:account-number` (where the country's BBAN
    defines them), via iban4j.

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

[0.11.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.11.0
[0.10.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.10.0
[0.9.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.9.0
[0.8.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.8.0
[0.7.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.7.0
[0.6.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.6.0
[0.5.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.5.0
[0.4.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.4.0
[0.3.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.3.0
[0.2.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.2.0
[0.1.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.1.0
