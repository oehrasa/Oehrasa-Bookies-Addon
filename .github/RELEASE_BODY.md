This release ships each Minecraft version as its own build (see the stonecutter
"node" matrix in `stonecutter.properties.toml`). Every jar carries the Minecraft
version it was built for — pick the one whose `+<version>` suffix matches your
install.

Each artifact is attested with GitHub build provenance (Sigstore) and carries
SHA256 sums in `SHA256SUMS.txt`. Verify the jars before installing:

```sh
sha256sum -c SHA256SUMS.txt
gh attestation verify <jar> --repo oehrasa/Oehrasa-Bookies-Addon
```
