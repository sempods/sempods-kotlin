package org.sempods

import com.google.inject.Injector
import org.junit.jupiter.api.BeforeEach

/**
 * What every test in this module needs from its injector, and nothing else.
 *
 * Deliberately narrow: this suite used to extend an application framework's base class for years
 * while using none of what it offered — a session DAO, a users facade and a request scope with zero
 * call sites here. The pod server has no session of that kind, so a base class that hands one out
 * was carrying a user domain into a module that is about to be published, for the sake of one
 * `injectMembers` call.
 *
 * Field injection rather than a constructor because JUnit constructs the test class itself: the
 * injector is a lazily built singleton shared by the whole suite (see
 * `SempodsIntegrationTest.sempodsInjector`), and each instance asks it to fill its own fields.
 */
open class SempodsTest(
  protected val injector: Injector,
) {

  @BeforeEach
  fun injectTestMembers() {
    injector.injectMembers(this)
  }
}
