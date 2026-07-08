# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/), and the project adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.27.1] - 2026-07-08

Docs-only release: cljdoc guide articles (Getting Started, Recipes, Adding an
Identifier Type) under doc/. No code changes.

## [0.27.0] - 2026-07-07

19 new types (138 → 157), in three groups. Every checksum verified against an
independently recomputed published number, per the corpus rules.

### Added - tech & media identifiers
- `:mac` — IEEE MAC-48/EUI-48 in all four written forms; `parse` exposes OUI,
  locally-administered and multicast bits; canonical colon `format`.
- `:bitcoin` — Bitcoin addresses: Base58Check (P2PKH/P2SH, double-SHA-256) and
  BIP-173 bech32 SegWit (mixed case rejected); `parse` exposes encoding/type.
- `:isrc` — ISO 3901 recording code, hyphenated `format` (IFPI database vector).
- `:imsi` — ITU E.212 subscriber identity, structural; `parse` exposes MCC.
- `:meid` — 3GPP2 mobile equipment identifier, base-16 Luhn check digit
  (TIA worked example); `parse` splits regional code/manufacturer/serial.
- `:isil` — ISO 15511 library identifier, structural.
- `:cfi` — ISO 10962 financial-instrument classification; `parse` maps the
  category letter to a keyword.

### Added - tax & business registries
- `:us-itin`, `:us-atin`, `:us-ptin` — IRS ITIN (group ranges 50-65/70-88/
  90-92/94-99), ATIN (group 93), PTIN (P + 8 digits).
- `:gb-utr` — HMRC Unique Taxpayer Reference, leading check digit
  (weights 6…2, lookup table); optional display "K" suffix stripped.
- `:dk-cvr` — Danish CVR company number, mod-11 (vector: Carlsberg A/S).
- `:fi-ytunnus` — Finnish Business ID; remainder-1 numbers rejected as never
  issued (vectors: Nokia Oyj, Kone Oyj); hyphenated `format`.
- `:de-idnr` — German tax IdNr, ISO 7064 MOD 11,10 plus the
  one-repeated-digit structural rule.

### Added - fintech payment formats
- `:ar-cbu` — Argentine CBU, dual 9713-pattern check digits; `parse` exposes
  bank/branch/account.
- `:es-ccc` — Spanish Código Cuenta Cliente, dual weighted mod-11 check pair
  (vector: the ISO 13616 Spanish IBAN worked example).
- `:eu-eic` — ENTSO-E Energy Identification Code, base-37 weighted check
  character.
- `:be-ogm` — Belgian structured payment reference (+++xxx/xxxx/xxxxx+++),
  mod 97 with 0 → 97; decorated `format`.
- `:ch-esr` — Swiss ESR/QR reference number, "modulo 10 recursive" check
  digit (SIX Annex B), grouped display `format`.

Deferred (no published algorithm or no verifiable real vector yet): `:sg-uen`
(ACRA does not publish the check-letter algorithm), `:es-cups`,
Czech/New-Zealand domestic bank account formats.

## [0.26.0] - 2026-06-27

### Added
- `:si-maticna` — Slovenia matična številka (company registration), weighted mod 11
  (vector: Krka d.d. Novo mesto, AJPES).
- `:iso11649` — ISO 11649 RF Creditor Reference for structured payments (ISO 7064
  Mod 97,10; standard worked example).
- `:it-aic` — Italy AIC pharmaceutical authorization code (Luhn-variant mod 10; vectors:
  Tachipirina / Angelini, AIFA registry).
- `:ca-bn` — Canada Business Number, 9-digit Luhn with optional BN15 program account
  (vector: Canadian Red Cross Society, CRA charity registry).

## [0.25.0] - 2026-06-27

### Added
- `:id-npwp` — Indonesia NPWP, classic 15-digit tax number (Luhn over the first 9;
  vector: PT Telkom Indonesia, IDX annual report).
- `:tr-vkn` — Turkey VKN (vergi kimlik numarası), 10-digit entity tax number (weighted
  mod-9 algorithm; vector: Türk Hava Yolları / Turkish Airlines, ETBİS).
- `:mx-rfc` — Mexico RFC, company (12-char) and person (13-char) taxpayer registry
  (SAT mod-11 check over the value table; vector: Petróleos Mexicanos / PEMEX, SAT CIF).
- `:grid` — GRid (Global Release Identifier), 18-char music release ID (ISO 7064
  Mod 37,36; vector: the GRid standard worked example).
- `:isan` — ISAN (ISO 15706), audiovisual work identifier with two ISO 7064 Mod 37,36
  check characters (root and version; vectors: ISAN standard worked examples).

## [0.24.1] - 2026-06-26

### Changed
- Relicense from EPL 1.0 to **EPL 2.0** (no code change; corrects the published POM
  license metadata, which lagged the source).

## [0.24.0] - 2026-06-26

### Added
- `:fr-siren` — France SIREN, 9-digit company identifier (Luhn; vectors: Renault S.A. /
  TotalEnergies SE, annuaire-entreprises.data.gouv.fr).
- `:fr-siret` — France SIRET, 14-digit establishment identifier (SIREN + 5-digit NIC, Luhn;
  vector: Renault S.A. siège social).
- `:se-orgnr` — Sweden organisationsnummer, 10-digit (Luhn, 3rd digit ≥ 2; vectors: Volvo AB /
  Ericsson AB, Bolagsverket).
- `:es-cif` — Spain CIF, org-letter + 7 digits + control digit or letter (vector: Banco
  Santander S.A., CNMV official register).
- `:nz-nzbn` — New Zealand Business Number, 13-digit GS1 GLN with `9429` prefix (mod-10;
  vectors: Air New Zealand Ltd / Fonterra Co-operative Group Ltd, nzbn.govt.nz).

## [0.23.0] - 2026-06-26

### Added
- `:ee-rk` — Estonia registry code (registrikood), 8-digit (mod-11 with weight-set fallback;
  vectors: Tallink Grupp / Telia Eesti, official e-Äriregister).
- `:uy-rut` — Uruguay RUT tax number, 12-digit (weighted mod 11; vector: ANTEL, state telecom).
- `:ec-ruc` — Ecuador RUC, 13-digit, all three taxpayer classes (natural / public entity /
  juridical, selected by the 3rd digit; vectors: Banco Pichincha juridical + SRI public entity).
- `:py-ruc` — Paraguay RUC tax number (base + check digit, weighted mod 11; vector: PETROPAR).
- `:gt-nit` — Guatemala NIT tax number (base + check digit or `K`, weighted mod 11; vector:
  Cementos Progreso S.A.).

## [0.22.0] - 2026-06-25

### Added
- `:au-acn` — Australia Company Number, 9-digit (weights 8..1, complement mod 10; vector: BHP Group Ltd).
- `:sk-ico` — Slovakia IČO, 8-digit (same Czechoslovak mod-11 algorithm as `:cz-ico`; vector: ESET spol. s r.o.).

### Changed
- README install snippets bumped to the current release (were stale at 0.18.0).

## [0.21.0] - 2026-06-25

### Added
- `:il-company` — Israel company/ID number, 9-digit (Israeli Luhn; vector: Teva Pharmaceutical
  Industries Ltd, public registrar number).

## [0.20.0] - 2026-06-25

### Added
- `:rs-pib` — Serbia tax number (PIB; ISO 7064 MOD 11,10; vector: NIS a.d. Novi Sad, public).
- `:pl-regon` — Poland REGON statistical number, 9-digit (weighted mod 11; vector: PKN Orlen S.A.).

## [0.19.0] - 2026-06-25

### Added
- `:ru-ogrn` — Russia OGRN company registration number (check = first-12 mod 11, mod 10;
  vectors: Sberbank / Gazprom public EGRUL numbers).
- `:vn-mst` — Vietnam tax code / MST (10-digit, optional 3-digit branch suffix; weighted mod 11;
  vectors: Vietcombank / Vinamilk, public listed companies).

## [0.18.0] - 2026-06-24

### Added
- `:do-rnc` — Dominican Republic RNC tax number (vectors: 3M Dominicana, from the DGII's own
  published list, and Claro).

## [0.17.0] - 2026-06-24

### Added
- `:iswc` — International Standard Musical Work Code (ISO 15707; vector: the CISAC worked
  example `T-034.524.680-1`).
- `:is-kennitala` — Iceland kennitala (vector: Icelandair ehf's public registry number).
- `:ve-rif` — Venezuela RIF tax number (letter-prefixed; vector: PDVSA's public number).

## [0.16.0] - 2026-06-24

### Added
- `:ua-edrpou` — Ukraine EDRPOU company registration number (both weight-set branches;
  vectors: Naftogaz and Ukrzaliznytsia public numbers).
- `:cn-usci` — China Unified Social Credit Identifier, the 18-character company code
  (mod-31 over the 31-symbol alphabet; vector: Tencent's public number).

## [0.15.0] - 2026-06-24

### Added
- `:iso6346` — ISO 6346 freight container (BIC) number, with the standard's character-value
  weighting and mod-11 check (vector: the standard's worked example `CSQU3054383`).
- `:ru-inn` — Russia INN tax number (10-digit legal entity and 12-digit individual variants;
  vector: Gazprom PJSC's public number).
- `:tw-gui` — Taiwan Unified Business Number / 統一編號 (vector: TSMC's public number).

## [0.14.0] - 2026-06-24

EU-27 VAT coverage is now complete (100 identifier types).

### Added
- VAT validators for the six remaining EU member states: `:nl-vat` (Netherlands, 9-digit mod-11
  + `B` suffix), `:lv-vat` (Latvia PVN, legal entity), `:bg-vat` (Bulgaria, 9-digit EIK/BULSTAT
  or 10-digit EGN), `:hr-vat` (Croatia, `HR` + OIB), plus `:cz-vat` and `:pt-vat` aliases over
  the existing IČO / NIF checks. Every new vector is a real company number confirmed live against
  VIES (Booking.com, Air Baltic, Sopharma, Škoda Auto, EDP), `:vies true`.

## [0.13.0] - 2026-06-24

### Changed
- Six EU VAT verification vectors (AT, SE, GR, SI, EE, HU) upgraded from checksum-valid but
  unregistered example numbers to numbers confirmed live-registered against VIES (TU Wien,
  Björn Lundén AB, OTE, Postojnska Jama, Movek Grupp, Richter Gedeon), each tagged `:vies true`
  so `lein test :integration` re-checks them against the official registry.

### Fixed
- Removed an auto-boxing warning in `stdnum.gs1-128/parse-raw` (the FNC1 offset accumulator was
  Object-typed); the build is now reflection- and boxing-warning clean again.

## [0.12.0] - 2026-06-24

GS1-128 barcode parsing, and a cited verification corpus that drives the test suite (with live VIES
re-confirmation of registered VAT numbers).

### Added
- New namespace `stdnum.gs1-128` for parsing GS1-128 (UCC/EAN-128) Application Identifier element
  strings. `parse` handles both the parenthesized
  human-readable form and the raw FNC1-delimited scan form, returning ordered
  `{:ai :label :value}` segments; weight/measure and amount AIs also report `:decimals` and a
  numeric `:decimal-value`. `parse-map` returns an AI-keyed map.

### Changed
- Verification is now driven by a cited corpus (`test/stdnum/vectors.edn`): every type maps to
  valid/invalid vectors with a mandatory `:source`. EU VAT entries confirmed live-registered are
  re-checked against the official VIES service under `lein test :integration`.

## [0.11.0] - 2026-06-24

Two major capability additions plus broader format coverage and two new VAT types (92 -> 94):
standalone check-digit algorithms and online VAT validation.

### Added
- More canonical `format`: `:hk-id` (`A123456(3)`), `:kr-brn` (`124-81-00998`), `:au-abn`
  (`51 824 753 556`), `:au-tfn` (`123 456 782`), `:no-org` (`974 760 673`).
- New namespace `stdnum.checkdigit` exposing the underlying algorithms as standalone primitives:
  `luhn-valid?` /
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
several identifiers. Everything verified against published example numbers; stays EPL.

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

[0.12.0]: https://github.com/jsavyasachi/stdnum-clj/releases/tag/0.12.0
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
