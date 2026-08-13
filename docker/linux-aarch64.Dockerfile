# Build container for the `linux-aarch64` platform. The filename encodes the platform token so the
# image tag, its Dockerfile, and the artifact platform stay one name. Same layout as
# djl-executorch-engine's docker/ and measly-java-learning/iree-runtime-dist's docker/.
#
# This image is what the live `linux-aarch64` matrix row builds inside, running on
# `ubuntu-24.04-arm`, so the shim and its QA are compiled and sanitizer-tested on native aarch64
# hardware.
#
# Thin specialization of the manylinux_2_28 build container: the base image is already the thing
# that bakes our glibc-2.28 floor, and this only adds what every build and QA run would otherwise
# install from scratch — the JNI headers, ninja, and the ASan runtime.
#
# Pinned to a dated tag, not `latest`. A floating base leaves no way to tell "the image changed"
# from "our code changed". The sibling djl-executorch-engine repo lost real time to exactly that:
# a 2026-08-05 base rebuild dropped a package and broke builds on a tree that had compiled clean
# hours earlier.
#
# NEVRAs verified 2026-08-11 by running this exact base tag on a native aarch64 host (not qemu):
# gcc is 14.2.1-11, and libasan resolves to the same version as the x86_64 image. That agreement
# is a convenience, not a rule — re-resolve per arch with `dnf list --showduplicates <pkg>` on the
# next base bump rather than copying the x86_64 values.
FROM quay.io/pypa/manylinux_2_28_aarch64:2026.06.04-1

# Pinned to an exact NEVRA rather than a bare package name, so a repo update to a newer default
# module stream cannot silently change what this image bakes in. If AlmaLinux retires this build,
# this RUN fails loudly (dnf cannot resolve the NEVRA) instead of drifting.
# Re-resolve with: dnf list --showduplicates gcc-toolset-14-libasan-devel
#
# gcc-toolset-14-libasan-devel  the ASan runtime for native/build_qa.sh. Held to 14.2.1-11.el8_10
#                      to match the base image's own compiler exactly (gcc 14.2.1-11); a libasan
#                      from a different toolset revision than the gcc that emitted the
#                      instrumentation is the classic source of confusing ASan link errors.
#                      build_qa.sh asserts these NEVRAs via rpm -q against the image's pins and
#                      installs nothing; outside the image it probes that the host toolchain can
#                      link -fsanitize=address/undefined.
# gcc-toolset-14-libubsan-devel  the UBSan runtime for native/build_qa.sh and
#                      native/ubsan_gate.sh. Same NEVRA as libasan above and for the same
#                      reason: it must match the gcc that emitted the instrumentation.
RUN dnf install -y \
      gcc-toolset-14-libasan-devel-14.2.1-11.el8_10 \
      gcc-toolset-14-libubsan-devel-14.2.1-11.el8_10 \
    && dnf clean all \
    && rm -rf /var/cache/dnf

# The scripts assert against these rather than installing anything: presence of
# IREE_DJL_PINNED_IMAGE means "you are in the pinned image, a missing tool is a broken image,
# not something to fix at run time". Keep the NEVRA here identical to the dnf line above --
# this is the single source of truth, and native/build_qa.sh reads it from the environment.
ENV IREE_DJL_PINNED_IMAGE=1
ENV IREE_DJL_TOOLSET_VER=14
ENV IREE_DJL_TOOLSET_NEVRA=14.2.1-11.el8_10
# IREE_DJL_NINJA_VERSION is the version string the pip ninja wheel's binary
# reports (`ninja --version`), NOT the pip package metadata version: pip
# installs ninja==1.13.0 (pin unchanged), but the Kitware jobserver-pipe wheel
# prints "1.13.0.git.kitware.jobserver-pipe-1". Both this image's assertion
# below and native/build.sh compare exactly against `ninja --version` output,
# so this must be the reported string, not the metadata version.
ENV IREE_DJL_NINJA_VERSION=1.13.0.git.kitware.jobserver-pipe-1

# The base ships no ninja, and native/build.sh configures with -G Ninja, so every build paid a
# `pip install ninja` before this. cp312 is the interpreter native/build.sh already puts on PATH.
# Symlinked into /usr/local/bin so `ninja` resolves however the container is entered, not only
# after build.sh's PATH line.
RUN /opt/python/cp312-cp312/bin/pip install --no-cache-dir ninja==1.13.0 \
    && ln -s /opt/python/cp312-cp312/bin/ninja /usr/local/bin/ninja

# JNI headers. We compile against jni.h and never link libjvm, so this is a headers-only need —
# but it used to cost a 113 MB RPM download on every single build, in CI and locally.
#
# The VERSIONED url, never https://corretto.aws/downloads/latest/... — that redirect is exactly
# what makes a layer non-reproducible. sha256 computed from the artifact; Corretto's
# latest_checksum endpoint serves MD5, so do not expect to find this published upstream.
# Corretto 8 (not a newer JDK) for the oldest supported jni.h and the widest runtime
# compatibility, matching what the Windows job binds via JAVA_HOME_8_X64.
ARG CORRETTO_URL=https://corretto.aws/downloads/resources/8.502.07.1/java-1.8.0-amazon-corretto-devel-1.8.0_502.b07-1.aarch64.rpm
ARG CORRETTO_SHA256=ce812e8ab602fd999d2576ee4ae0eb82116017c7304dfb91601b5e312a6fc48c

# rpm2archive, not rpm2cpio: this image ships no cpio. The find-then-symlink indirection is
# deliberate — hardcoding the current extraction path
# (/opt/corretto/usr/lib/jvm/java-1.8.0-amazon-corretto) means a Corretto directory rename yields
# an image with a dangling JAVA_HOME and no error until a shim build dies deep in a CMake
# configure. Everything is removed in the same layer so the RPM is not carried in the image.
RUN curl -fL -o /tmp/corretto.rpm "${CORRETTO_URL}" \
    && echo "${CORRETTO_SHA256}  /tmp/corretto.rpm" | sha256sum -c - \
    && rpm2archive /tmp/corretto.rpm \
    && mkdir -p /opt/corretto \
    && tar -C /opt/corretto -xzf /tmp/corretto.rpm.tgz \
    && jni_h="$(find /opt/corretto -path '*/include/jni.h' | head -1)" \
    && test -n "${jni_h}" || { echo "no include/jni.h found in the extracted Corretto RPM"; exit 1; } \
    && ln -s "${jni_h%/include/jni.h}" /opt/corretto-jdk \
    && rm -f /tmp/corretto.rpm /tmp/corretto.rpm.tgz

ENV JAVA_HOME=/opt/corretto-jdk

# Fail at image-build time, not three steps into a shim build, if a pin ever stops delivering what
# it is here for.
RUN test -f "${JAVA_HOME}/include/jni.h" \
      || { echo "JAVA_HOME=${JAVA_HOME} has no include/jni.h"; exit 1; } \
    && test -f "${JAVA_HOME}/include/linux/jni_md.h" \
      || { echo "JAVA_HOME=${JAVA_HOME} has no include/linux/jni_md.h"; exit 1; } \
    && command -v ninja >/dev/null \
      || { echo "ninja is not on PATH"; exit 1; } \
    && ninja --version \
    && rpm -q "gcc-toolset-${IREE_DJL_TOOLSET_VER}-libasan-devel-${IREE_DJL_TOOLSET_NEVRA}" \
      || { echo "libasan NEVRA not installed as pinned"; exit 1; } \
    && rpm -q "gcc-toolset-${IREE_DJL_TOOLSET_VER}-libubsan-devel-${IREE_DJL_TOOLSET_NEVRA}" \
      || { echo "libubsan NEVRA not installed as pinned"; exit 1; } \
    && test "$(ninja --version)" = "${IREE_DJL_NINJA_VERSION}" \
      || { echo "ninja is $(ninja --version), expected ${IREE_DJL_NINJA_VERSION}"; exit 1; }
