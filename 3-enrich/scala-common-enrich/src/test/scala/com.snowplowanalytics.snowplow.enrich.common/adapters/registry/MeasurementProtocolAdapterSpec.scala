/*
 * Copyright (c) 2012-2017 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package com.snowplowanalytics.snowplow.enrich.common
package adapters
package registry

// Joda-Time
import org.joda.time.DateTime

// Scalaz
import scalaz._
import Scalaz._

// Specs2
import org.specs2.Specification
import org.specs2.matcher.DataTables
import org.specs2.scalaz.ValidationMatchers

// Snowplow
import loaders.{CollectorApi, CollectorSource, CollectorContext, CollectorPayload}

class MeasurementProtocolAdapterSpec extends Specification with DataTables with ValidationMatchers {
  def is = s2"""
    This is a specification to test the MeasurementProtocolAdapter functionality
    toRawEvents returns a failNel if the query string is empty               $e1
    toRawEvents returns a failNel if there is no t param in the query string $e2
    toRawEvents returns a failNel if there are no corresponding hit types    $e3
    toRawEvents returns a succNel if the payload is correct                  $e4
    toRawEvents returns a succNel containing the added contexts              $e5
    toRawEvents returns a succNel containing the direct mappings             $e6
    toRawEvents returns a succNel containing properly typed contexts         $e7
    toRawEvents returns a succNel containing pageview as a context           $e8
    toRawEvents returns a succNel with composite contexts                    $e9
    breakDownCompositeField should work properly                             $e20
  """

  implicit val resolver = SpecHelpers.IgluResolver

  val api = CollectorApi("com.google.analytics.measurement-protocol", "v1")
  val source = CollectorSource("clj-tomcat", "UTF-8", None)
  val context = CollectorContext(DateTime.parse("2013-08-29T00:18:48.000+00:00").some,
    "37.157.33.123".some, None, None, Nil, None)

  val static = Map(
    "tv" -> "com.google.analytics.measurement-protocol-v1",
    "e"  -> "ue",
    "p" -> "srv"
  )

  val hitContext = (hitType: String) => s"""
    |{
      |"schema":"iglu:com.google.analytics.measurement-protocol/hit/jsonschema/1-0-0",
      |"data":{"hitType":"$hitType"}
    |}""".stripMargin.replaceAll("[\n\r]", "")

  def e1 = {
    val params = SpecHelpers.toNameValuePairs()
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)
    actual must beFailing(NonEmptyList(
      "Querystring is empty: no MeasurementProtocol event to process"))
  }

  def e2 = {
    val params = SpecHelpers.toNameValuePairs("dl" -> "document location")
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)
    actual must beFailing(NonEmptyList(
      "No MeasurementProtocol t parameter provided: cannot determine hit type"))
  }

  def e3 = {
    val params = SpecHelpers.toNameValuePairs("t" -> "unknown", "dl" -> "document location")
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)
    actual must beFailing(NonEmptyList(
      "No matching MeasurementProtocol hit type for hit type unknown"))
  }

  def e4 = {
    val params = SpecHelpers.toNameValuePairs(
      "t"  -> "pageview",
      "dh" -> "host name",
      "dp" -> "path"
    )
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)

    val expectedJson =
      """|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
           |"data":{
             |"schema":"iglu:com.google.analytics.measurement-protocol/page_view/jsonschema/1-0-0",
             |"data":{
               |"documentHostName":"host name",
               |"documentPath":"path"
             |}
           |}
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedCO =
      s"""|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
           |"data":[${hitContext("pageview")}]
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedParams = static ++ Map("ue_pr" -> expectedJson, "co" -> expectedCO)
    actual must beSuccessful(NonEmptyList(RawEvent(api, expectedParams, None, source, context)))
  }

  def e5 = {
    val params = SpecHelpers.toNameValuePairs(
      "t"   -> "pageview",
      "dh"  -> "host name",
      "cid" -> "client id",
      "v"   -> "protocol version"
    )
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)

    val expectedUE =
      """|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
           |"data":{
             |"schema":"iglu:com.google.analytics.measurement-protocol/page_view/jsonschema/1-0-0",
             |"data":{
               |"documentHostName":"host name"
             |}
           |}
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedCO =
      s"""|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
           |"data":[${hitContext("pageview")},{
             |"schema":"iglu:com.google.analytics.measurement-protocol/user/jsonschema/1-0-0",
             |"data":{"clientId":"client id"}
           |},{
             |"schema":"iglu:com.google.analytics.measurement-protocol/general/jsonschema/1-0-0",
             |"data":{"protocolVersion":"protocol version"}
           |}]
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedParams = static ++ Map("ue_pr" -> expectedUE, "co" -> expectedCO)
    actual must beSuccessful(NonEmptyList(RawEvent(api, expectedParams, None, source, context)))
  }

  def e6 = {
    val params = SpecHelpers.toNameValuePairs("t" -> "pageview", "dp" -> "path", "uip" -> "some ip")
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)

    val expectedUE =
      """|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
           |"data":{
             |"schema":"iglu:com.google.analytics.measurement-protocol/page_view/jsonschema/1-0-0",
             |"data":{"documentPath":"path"}
           |}
         |}""".stripMargin.replaceAll("[\n\r]", "")
    // uip is part of the session context
    val expectedCO =
      s"""|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
           |"data":[${hitContext("pageview")},{
             |"schema":"iglu:com.google.analytics.measurement-protocol/session/jsonschema/1-0-0",
             |"data":{"ipOverride":"some ip"}
           |}]
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedParams = static ++ Map("ue_pr" -> expectedUE, "co" -> expectedCO, "ip" -> "some ip")
    actual must beSuccessful(NonEmptyList(RawEvent(api, expectedParams, None, source, context)))
  }

  def e7 = {
    val params = SpecHelpers.toNameValuePairs(
      "t" -> "item", "in" -> "item name", "ip" -> "12.228", "iq" -> "12", "aip" -> "0")
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)

    val expectedUE =
      """|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
           |"data":{
             |"schema":"iglu:com.google.analytics.measurement-protocol/item/jsonschema/1-0-0",
             |"data":{
               |"price":12.23,
               |"name":"item name",
               |"quantity":12
             |}
           |}
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedCO =
      s"""|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
           |"data":[{
             |"schema":"iglu:com.google.analytics.measurement-protocol/general/jsonschema/1-0-0",
             |"data":{"anonymizeIp":false}
           |},${hitContext("item")}]
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedParams = static ++ Map("ue_pr" -> expectedUE, "co" -> expectedCO,
      // ip, iq and in are direct mappings too
      "ti_pr" -> "12.228", "ti_qu" -> "12", "ti_nm" -> "item name")
    actual must beSuccessful(NonEmptyList(RawEvent(api, expectedParams, None, source, context)))
  }

  def e8 = {
    val params = SpecHelpers.toNameValuePairs(
      "t" -> "exception", "exd" -> "ex desc", "exf" -> "1", "dh" -> "host name")
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)

    val expectedUE =
      """|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
           |"data":{
             |"schema":"iglu:com.google.analytics.measurement-protocol/exception/jsonschema/1-0-0",
             |"data":{"description":"ex desc","isFatal":true}
           |}
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedCO =
      s"""|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
           |"data":[${hitContext("exception")},{
             |"schema":"iglu:com.google.analytics.measurement-protocol/page_view/jsonschema/1-0-0",
             |"data":{"documentHostName":"host name"}
           |}]
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedParams = static ++ Map("ue_pr" -> expectedUE, "co" -> expectedCO)
    actual must beSuccessful(NonEmptyList(RawEvent(api, expectedParams, None, source, context)))
  }

  def e9 = {
    val params = SpecHelpers.toNameValuePairs(
      "t" -> "transaction", "ti" -> "tr", "cu" -> "EUR", "pr12id" -> "ident", "pr12cd42" -> "val")
    val payload = CollectorPayload(api, params, None, None, source, context)
    val actual = MeasurementProtocolAdapter.toRawEvents(payload)

    val expectedUE =
      """|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0",
           |"data":{
             |"schema":"iglu:com.google.analytics.measurement-protocol/transaction/jsonschema/1-0-0",
             |"data":{"currencyCode":"EUR","id":"tr"}
           |}
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedCO =
      s"""|{
           |"schema":"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1",
           |"data":[${hitContext("transaction")},{
             |"schema":"iglu:com.google.analytics.measurement-protocol/product/jsonschema/1-0-0",
             |"data":{"sku":"ident","currencyCode":"EUR","index":12}
           |},{
             |"schema":"iglu:com.google.analytics.measurement-protocol/product_custom_dimension/jsonschema/1-0-0",
             |"data":{"dimensionIndex":42,"value":"val","productIndex":12}
           |}]
         |}""".stripMargin.replaceAll("[\n\r]", "")
    val expectedParams = static ++ Map("ue_pr" -> expectedUE, "co" -> expectedCO,
      "tr_cu" -> "EUR", "tr_id" -> "tr")
    actual must beSuccessful(NonEmptyList(RawEvent(api, expectedParams, None, source, context)))
  }

  def e20 = {
    import MeasurementProtocolAdapter._
    breakDownCompositeField("pr") must beSuccessful((List("pr"), List.empty[String]))
    breakDownCompositeField("pr12id") must beSuccessful((List("pr", "id"), List("12")))
    breakDownCompositeField("12") must beFailing("Malformed composite field name: 12")
    breakDownCompositeField("") must beFailing("Malformed composite field name: ")

    breakDownCompositeField("pr12id", "identifier", "IF") must beSuccessful(
      Map("IFpr" -> "12", "prid" -> "identifier"))
    breakDownCompositeField("pr12cm42", "value", "IF") must beSuccessful(
      Map("IFpr" -> "12", "IFcm" -> "42", "prcm" -> "value"))
    breakDownCompositeField("pr", "value", "IF") must beSuccessful(Map("pr" -> "value"))
    breakDownCompositeField("pr", "", "IF") must beSuccessful(Map("pr" -> ""))
    breakDownCompositeField("pr12", "val", "IF") must beSuccessful(Map("IFpr" -> "12", "pr" -> "val"))
  }
}