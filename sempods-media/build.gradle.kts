plugins {
  `java-library`
}

description = "The media contract a pod and its clients share."

dependencies {
  // None. Two types, `java.net.URI` and a media type string: what the upload route means is the
  // whole of this module, and both sides of it — the pod's endpoint and a client — read it from here
  // rather than from each other.
}
