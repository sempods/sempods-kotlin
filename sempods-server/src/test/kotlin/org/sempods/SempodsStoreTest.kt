package org.sempods

/**
 * The suite's injector and its singletons, and no running server.
 *
 * [SempodsIntegrationTest] starts Jetty in its `@BeforeAll` because its subjects are reached over
 * HTTP. A test of the application layer has no route to call and no response to read, so it takes
 * this instead — and a subject that turns out to need the server fails here rather than passing on
 * one a sibling happened to start.
 *
 * The database is the real one all the same: what these classes decide is inseparable from what
 * their stores do atomically, and a fake store would test the fake.
 */
open class SempodsStoreTest : SempodsTest(injector = sempodsInjector)
