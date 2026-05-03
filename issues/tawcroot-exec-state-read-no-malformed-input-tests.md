# tawcroot: `exec_state_read` has no fuzz / malformed-input coverage

`test_exec_state.c` includes a few hand-crafted bad inputs but the
parser surface is wider than that. Notable gaps:

- **Overlapping ranges** — `argv_off[i]` pointing into the path
  string area (or vice versa) should be rejected; no test asserts it.
- **Random-byte property test** — generate random buffers with the
  magic spliced in and assert the parser returns an error rather
  than crashing or returning bogus offsets.

These are the inputs the parser would see in a corrupted memfd
(memory pressure, partial write before exec, kernel bug). Today's
tests only exercise the happy path plus a handful of negatives.

## Suggested approach

A QuickCheck-style harness via cleat: generate random length, fuzz
each field independently, then compose. If the parser accepts the
input, run the same buffer through the validator and assert
self-consistency. If it rejects, ensure no out-of-bounds read
occurred (run under sanitizers in the host build).

## Severity

Low. The memfd is process-private and we control the writer, so the
likely failure mode is "we shipped a bug" rather than "an attacker
fed us a bad buffer."
