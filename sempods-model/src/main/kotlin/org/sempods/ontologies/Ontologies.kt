package org.sempods.ontologies

import org.eclipse.rdf4j.model.impl.SimpleNamespace
import org.eclipse.rdf4j.model.util.Values

object Ontologies {

  /**
   * The vocabularies here are **standards** — terms a stranger's pod is as likely to hold as this
   * one. An application's own namespace does not belong beside them: nothing in the pod server or
   * the pod client reads such terms, only that application's own code does, and a constant here
   * says the opposite to every reader of the library. One used to sit in this object and now lives
   * with its owner.
   */

  object Hydra {

    val NS = SimpleNamespace("hydra", "http://www.w3.org/ns/hydra/core#")

    object Types {
      val Collection = Values.iri(NS, "Collection")
    }

    object Properties {
      val member = Values.iri(NS, "member")
      val next = Values.iri(NS, "next")
    }
  }

  object SCHEMA_ORG {

    val NS = SimpleNamespace("schema", "https://schema.org/")

    object Types {
      val Event = org.eclipse.rdf4j.model.util.Values.iri(NS, "Event")
      val ImageObject = org.eclipse.rdf4j.model.util.Values.iri(NS, "ImageObject")
      val Offer = org.eclipse.rdf4j.model.util.Values.iri(NS, "Offer")
      val Place = org.eclipse.rdf4j.model.util.Values.iri(NS, "Place")
      val PostalAddress = org.eclipse.rdf4j.model.util.Values.iri(NS, "PostalAddress")
    }

    object Properties {
      val address = org.eclipse.rdf4j.model.util.Values.iri(NS, "address")
      val addressCountry = org.eclipse.rdf4j.model.util.Values.iri(NS, "addressCountry")
      val addressLocality = org.eclipse.rdf4j.model.util.Values.iri(NS, "addressLocality")
      val contentUrl = org.eclipse.rdf4j.model.util.Values.iri(NS, "contentUrl")
      val dateModified = org.eclipse.rdf4j.model.util.Values.iri(NS, "dateModified")
      val description = org.eclipse.rdf4j.model.util.Values.iri(NS, "description")
      val encodingFormat = org.eclipse.rdf4j.model.util.Values.iri(NS, "encodingFormat")
      val endDate = org.eclipse.rdf4j.model.util.Values.iri(NS, "endDate")
      val eventAttendanceMode = org.eclipse.rdf4j.model.util.Values.iri(NS, "eventAttendanceMode")
      val eventStatus = org.eclipse.rdf4j.model.util.Values.iri(NS, "eventStatus")
      val identifier = org.eclipse.rdf4j.model.util.Values.iri(NS, "identifier")
      val image = org.eclipse.rdf4j.model.util.Values.iri(NS, "image")
      val keywords = org.eclipse.rdf4j.model.util.Values.iri(NS, "keywords")
      val latitude = org.eclipse.rdf4j.model.util.Values.iri(NS, "latitude")
      val location = org.eclipse.rdf4j.model.util.Values.iri(NS, "location")
      val longitude = org.eclipse.rdf4j.model.util.Values.iri(NS, "longitude")
      val name = org.eclipse.rdf4j.model.util.Values.iri(NS, "name")
      val offers = org.eclipse.rdf4j.model.util.Values.iri(NS, "offers")
      val postalCode = org.eclipse.rdf4j.model.util.Values.iri(NS, "postalCode")
      val sameAs = org.eclipse.rdf4j.model.util.Values.iri(NS, "sameAs")
      val startDate = org.eclipse.rdf4j.model.util.Values.iri(NS, "startDate")
      val streetAddress = org.eclipse.rdf4j.model.util.Values.iri(NS, "streetAddress")
      val thumbnailUrl = org.eclipse.rdf4j.model.util.Values.iri(NS, "thumbnailUrl")
      val url = org.eclipse.rdf4j.model.util.Values.iri(NS, "url")
    }

    object Values {
      val EventCancelled = org.eclipse.rdf4j.model.util.Values.literal("https://schema.org/EventCancelled")
      val EventScheduled = org.eclipse.rdf4j.model.util.Values.literal("https://schema.org/EventScheduled")
      val OfflineEventAttendanceMode =
        org.eclipse.rdf4j.model.util.Values.literal("https://schema.org/OfflineEventAttendanceMode")
    }
  }
}
