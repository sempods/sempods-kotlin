# The Ubuntu release is named rather than left to the `25-jre` tag: the package name below and the
# `java.security` keys are checked against this release, and a move to the next one should be a
# deliberate edit, not something a scheduled rebuild picks up on its own.
FROM eclipse-temurin:25-jre-resolute

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

# The JVM caches DNS answers, 30 seconds by default and 10 for a failed lookup. Both are set to 0 so
# that a container or service whose address changes is reached at the new one on the next
# connection. Appended rather than edited in place: the last occurrence of a key wins, so this holds
# whatever the upstream file looks like.
#
# tcmalloc replaces glibc malloc for the JVM's native allocations. Under glibc's per-thread arenas
# they fragment, and the resident size keeps growing although the heap does not.
# `/etc/ld.so.preload` loads it into every process in the container. The package is not
# version-pinned: this image is rebuilt weekly to pick up security fixes, and Ubuntu drops a
# superseded version from the archive, so a pin would fail exactly the rebuild that carries the fix.
#
# The last two lines fail the build if either setting did not take effect. The second reads the
# JVM's whole output: with `pipefail`, a `grep -q` closing the pipe early would fail it instead.
# hadolint ignore=DL3008
RUN printf '%s\n' 'networkaddress.cache.ttl=0' 'networkaddress.cache.negative.ttl=0' \
      >> "$JAVA_HOME/conf/security/java.security" && \
    apt-get update && \
    apt-get install -y --no-install-recommends libtcmalloc-minimal4t64 && \
    apt-get clean && rm -rf /var/lib/apt/lists/* && \
    dpkg -L libtcmalloc-minimal4t64 | grep '/libtcmalloc_minimal\.so\.4$' > /etc/ld.so.preload && \
    grep -q libtcmalloc_minimal /proc/self/maps && \
    java -XshowSettings:security:properties -version 2>&1 | grep 'networkaddress.cache.ttl=0' > /dev/null
