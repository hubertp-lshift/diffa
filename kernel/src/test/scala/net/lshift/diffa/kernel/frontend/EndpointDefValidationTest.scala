package net.lshift.diffa.kernel.frontend

import org.junit.Test
import net.lshift.diffa.kernel.config.RangeCategoryDescriptor
import scala.collection.JavaConversions._
import org.junit.Assert.assertEquals

/**
 * Verify that EndpointDef constraints are enforced.
 */
class EndpointDefValidationTest extends DefValidationTestBase {
  @Test
  def shouldAcceptEndpointWithNameThatIsMaxLength {
    List(
      "a",
      "a" * DefaultLimits.KEY_LENGTH_LIMIT
    ) foreach {
      key =>
        EndpointDef(name = key).validate("config/endpoint")
    }
  }

  @Test
  def shouldRejectEndpointWithoutName {
    validateError(new EndpointDef(name = null), "config/endpoint[name=null]: name cannot be null or empty")
  }

  @Test
  def shouldRejectEndpointWithNameThatIsTooLong {
    validateExceedsMaxKeyLength("config/endpoint[name=%s]: name",
      name => EndpointDef(name = name))
  }

  @Test
  def shouldRejectEndpointWithScanUrlThatIsTooLong {
    validateExceedsMaxUrlLength("config/endpoint[name=a]: scanUrl",
      url => EndpointDef(name = "a", scanUrl = url))
  }

  @Test
  def shouldRejectEndpointWithContentRetrievalUrlThatIsTooLong {
    validateExceedsMaxUrlLength("config/endpoint[name=a]: contentRetrievalUrl",
      url => EndpointDef(name = "a", contentRetrievalUrl = url))
  }

  @Test
  def shouldRejectEndpointWithVersionGenerationUrlThatIsTooLong {
    validateExceedsMaxUrlLength("config/endpoint[name=a]: versionGenerationUrl",
      url => EndpointDef(name = "a", versionGenerationUrl = url))
  }

  @Test
  def shouldRejectEndpointWithInboundUrlThatIsTooLong {
    validateExceedsMaxUrlLength("config/endpoint[name=a]: inboundUrl",
      url => EndpointDef(name = "a", inboundUrl = url))
  }

  @Test
  def shouldRejectEndpointWithUnnamedCategory() {
    validateError(
      new EndpointDef(name = "e1", categories = Map("" -> new RangeCategoryDescriptor())),
      "config/endpoint[name=e1]/category[name=]: name cannot be null or empty")
  }

  @Test
  def shouldRejectEndpointWithInvalidCategoryDescriptor() {
    validateError(
      new EndpointDef(name = "e1", categories = Map("cat1" -> new RangeCategoryDescriptor())),
      "config/endpoint[name=e1]/category[name=cat1]: dataType cannot be null or empty")
  }

  @Test
  def shouldDefaultToAsciiOrdering() = {
    val endpoint = EndpointDef(name="dummy")
    assertEquals(DiffaConfig.ASCII_COLLATION, endpoint.idOrdering)
  }

  def shouldAcceptEndpointWithAsciiCollation {
    val endpoint = EndpointDef(name="dummy", idOrdering="ascii")
    assertIsValid(endpoint)

    assertEquals(DiffaConfig.ASCII_COLLATION, endpoint.idOrdering)
  }
  @Test
  def shouldAcceptEndpointWithUnicodeCollation {
    val endpoint = EndpointDef(name="dummy", idOrdering="unicode")
    assertIsValid(endpoint)
    assertEquals(DiffaConfig.UNICODE_COLLATION, endpoint.idOrdering)
  }

  @Test
  def shouldRejectInvalidCollation {
    val endpoint = EndpointDef(name="dummy", idOrdering="dummy")
    validateError(endpoint, "config/endpoint[name=dummy]: collation is invalid. dummy is not a member of the set Set(ascii, unicode)")
  }

}
