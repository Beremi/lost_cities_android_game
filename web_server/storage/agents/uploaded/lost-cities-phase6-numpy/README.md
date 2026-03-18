# Export Model Package

This folder contains a web-agent package for Lost Cities that:

- uses the strongest exported checkpoint currently available in this repo
- runs with pure Python plus `numpy` only at runtime
- is compliant with the web-agent API described in the project notes

## Files

- `agent_manifest.json`: package manifest for the web runtime
- `agent.py`: NumPy-only runtime agent
- `export_package.py`: converts the chosen PyTorch checkpoint into `data/model.npz`
- `data/model.npz`: exported model weights for the runtime agent
- `data/model_meta.json`: metadata about the exported checkpoint

## Capability note

The training system uses a full-turn policy over `(play/discard, draw source)` and can be wrapped in search inside the arena.

This package exports the strongest learned network weights, but the runtime agent itself is **policy-only**:

- on `play` phase it scores all legal full-turn combinations with the NumPy network
- it returns the chosen play/discard action immediately
- it caches the chosen draw half and returns it on the following `draw` phase

So this package preserves the strongest learned model available here, but it does **not** reproduce the full PyTorch arena search stack.

## Rebuild the exported weights

```bash
python export_model/export_package.py
```

## Create a zip

```bash
cd export_model
zip -r lost_cities_phase6_numpy.zip agent_manifest.json agent.py data README.md
```
