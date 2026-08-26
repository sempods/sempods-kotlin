plugins {
  `java-library`
}

dependencies {

  // `sempods-commons` for the hashing helpers and `BaseModule`; nothing else. No `sempods-commons-jaxrs`, no
  // Ktor, no application framework — the point of this module is that three services with three
  // different HTTP stacks can all depend on it without inheriting each other's.
  // `api`, because `BaseModule` is `SempodsAuthCoreModule`'s supertype.
  api(project(":sempods-commons"))

  // `api`: the driver's own types are part of this module's surface, and all three stores put them
  // there. `MongoDatabase` is in every constructor. `Document` is the receiver of the payload
  // codecs `OneTimeStore` and `RefreshTokenStore` are built from — deliberately, so a codec can use
  // the `commons-mongo` helpers and keep the wire contract with them — and six store classes across
  // `:sempods-server`, `:sempods-auth` and `:sempods-mcp` write one. `Bson` is what
  // `RefreshTokenStore.revokeWhere` and `deleteWhere` take, which is how a service expresses a
  // revocation the mechanism cannot. `:sempods-commons-mongo` supplies the document helpers and
  // stays behind the wall.
  // Note this is the driver, not a framework: it constrains nobody's HTTP stack.
  implementation(project(":sempods-commons-mongo"))
  api(libs.mongodb)
  api(libs.bson)

  // `api` because the OAuth/OIDC surface speaks Nimbus types: a consumer verifying an id_token
  // handles `JWTClaimsSet` itself, so hiding the library would only force it to re-declare it.
  api(libs.jwt)

  // The protocol message layer. `implementation` rather than `api`: what this module hands a
  // consumer is its own types, so the SDK stays an internal choice and a service that wires OAuth
  // by hand does not inherit it. Worth its ~1 MB because every defect this project's own review
  // found in the hand-written server half was in exactly what it replaces — a token response
  // cannot be built without an access token, and an authorization request cannot be parsed
  // without its `response_type` being checked.
  implementation(libs.oidcSdk)

  // Same reasoning as `sempods-commons` and `sempods-commons-mongo`: `SempodsAuthCoreModule` is the only class
  // here that needs Guice, and a consumer wiring these stores by hand must not inherit a DI
  // container to get a PKCE verifier.
  compileOnly(libs.guice)

  implementation(libs.bundles.logging)

  testImplementation(libs.guice)
  testImplementation(libs.bundles.test)
}
