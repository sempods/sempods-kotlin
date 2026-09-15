package org.sempods.client.core

/**
 * A permission a caller holds on a context: the specification's `read`, `write` and `manage`.
 *
 * A pod reports them collapsed, so `write` and `manage` arrive together with `read`.
 */
enum class SempodsContextPermission {
  READ,
  WRITE,
  MANAGE,
}
