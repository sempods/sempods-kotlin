# Java base image

`ghcr.io/sempods/java-base` is the image the Jib builds of the three services start from. Any other
JVM service may use it too. It is `eclipse-temurin:25-jre-resolute` with two changes:

| Change | Effect on a running service |
|---|---|
| JVM DNS cache disabled (`networkaddress.cache.ttl=0`, also for failed lookups) | A host whose address changes is reached at the new address on the next connection |
| tcmalloc preloaded through `/etc/ld.so.preload` | Every process in the container allocates native memory with tcmalloc instead of glibc malloc |

The [Dockerfile](Dockerfile) gives the reasons, and its build fails if either change does not take
effect.

## Tags

| Tag | Moves |
|---|---|
| `latest` | with every build |
| `YYYYMMDD`, for example `20261102` | only when a second build runs on the same day |

Both are multi-platform images for `linux/amd64` and `linux/arm64`.

## Builds

The [workflow](../../.github/workflows/java-base.yml) rebuilds the image every Monday to pick up
JDK and Ubuntu updates. It also publishes on every change to this directory on `main`, and can be
started by hand. A pull request that touches the image builds it without publishing.

A local build of one platform:

<!-- doc-example: illustrative; checked against deployments/java-base/Dockerfile -->
```bash
docker build --platform linux/arm64 -t java-base deployments/java-base
```

To see which tcmalloc version the next build installs:

<!-- doc-example: illustrative; package name from deployments/java-base/Dockerfile -->
```bash
docker run --rm eclipse-temurin:25-jre-resolute bash -c "apt-get update -qq && apt-cache policy libtcmalloc-minimal4t64"
```
