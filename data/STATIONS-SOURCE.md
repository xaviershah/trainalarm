# Station dataset: source and licence

`stations.json` lists every National Rail station in Great Britain that NaPTAN
gives a CRS code and coordinates for: `crs`, `name`, `lat`, `lon`.

## Source

- Dataset: National Public Transport Access Nodes (NaPTAN), Department for Transport.
- Retrieved: 2026-10-06 from
  `https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910`
  (ATCO area 910 = rail).
- Licence: Open Government Licence v3.0,
  https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/

## Attribution (must be shown to users in the app, at the UI stage)

> Contains public sector information licensed under the Open Government Licence v3.0.
> Station data: Department for Transport, National Public Transport Access Nodes (NaPTAN).

## Regenerating

```bash
mkdir -p tools/stations/.cache
curl -sf -o tools/stations/.cache/naptan-rail.xml "https://naptan.api.dft.gov.uk/v1/access-nodes?dataFormat=xml&atcoAreaCodes=910"
python3 tools/stations/generate_stations.py --xml tools/stations/.cache/naptan-rail.xml
python3 tools/stations/sync_stations.py
```

## Rules applied (tools/stations/generate_stations.py)

- Only StopPoints with a CRS code (`StopClassification/OffStreet/Rail/AnnotatedRailRef/CrsRef`).
- Inactive records dropped. Records with no coordinates dropped (11 codes, e.g. PDX, FDX:
  NaPTAN has no coordinates for them, so none are invented).
- Names have a trailing " Rail Station" / " Station" removed; otherwise they are NaPTAN's
  names, so "Edinburgh" not "Edinburgh Waverley".
- Same-station duplicates (same name, within 500 m) keep the lowest ATCO code.
- Ten codes with conflicting names are decided in `tools/stations/overrides.json`, each with a reason.
- Coordinates rounded to 6 decimal places.

## Known gaps

- The 11 coordinate-less codes are missing, so a lookup for them returns nothing.
- Names are not always the public name (see above).
