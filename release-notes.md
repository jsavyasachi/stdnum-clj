
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

### Changed
- Relicense from EPL 1.0 to **EPL 2.0** (no code change; corrects the published POM
  license metadata, which lagged the source).

