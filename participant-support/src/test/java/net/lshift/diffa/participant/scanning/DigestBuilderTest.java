package net.lshift.diffa.participant.scanning;

import org.apache.commons.codec.digest.DigestUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Test cases for the digest builder.
 */
public class DigestBuilderTest {
  private static final ScanAggregation bizDateAggregation =
      new DateAggregation("bizDate", DateGranularityEnum.Daily);
  private static final ScanAggregation someStringAggregation =
      new ByNameAggregation("someString");
  private static final List<ScanAggregation> aggregations = Arrays.asList(
      bizDateAggregation, someStringAggregation);

  private static final DateTime JUN_6_2009_1 = new DateTime(2009, 6, 6, 12, 45, 12, 0, DateTimeZone.UTC);
  private static final DateTime JUN_6_2009_2 = new DateTime(2009, 6, 6, 15, 32, 16, 0, DateTimeZone.UTC);
  private static final DateTime JUN_7_2009_1 = new DateTime(2009, 6, 7, 13, 51, 31, 0, DateTimeZone.UTC);

  @Test
  public void shouldReturnEmptyDigestsForNoInput() {
    DigestBuilder builder = new DigestBuilder(aggregations);
    assertEquals(0, builder.toDigests().size());
  }

  @Test
  public void shouldObserveAllAggregationFactors() {
    DigestBuilder builder = new DigestBuilder(aggregations);

    builder.add("id1", createAttrMap(JUN_6_2009_1, "a"), "vsn1");
    builder.add("id2", createAttrMap(JUN_7_2009_1, "b"), "vsn2");
    builder.add("id3", createAttrMap(JUN_6_2009_2, "c"), "vsn3");
    builder.add("id4", createAttrMap(JUN_6_2009_2, "a"), "vsn4");

    assertEquals(
      new HashSet<ScanResultEntry>(Arrays.asList(
        ScanResultEntry.forAggregate(DigestUtils.md5Hex("vsn1" + "vsn4"), createAttrMap("2009-06-06", "a")),
        ScanResultEntry.forAggregate(DigestUtils.md5Hex("vsn2"), createAttrMap("2009-06-07", "b")),
        ScanResultEntry.forAggregate(DigestUtils.md5Hex("vsn3"), createAttrMap("2009-06-06", "c"))
      )),
      new HashSet<ScanResultEntry>(builder.toDigests()));
  }

  @Test
  public void shouldObserveAttributesThatArentAggregationFactors() {
    DigestBuilder builder = new DigestBuilder(Arrays.asList(bizDateAggregation));

    builder.add("id1", createAttrMap(JUN_6_2009_1, "a"), "vsn1");
    builder.add("id2", createAttrMap(JUN_7_2009_1, "b"), "vsn2");
    builder.add("id3", createAttrMap(JUN_6_2009_2, "c"), "vsn3");
    builder.add("id4", createAttrMap(JUN_6_2009_2, "a"), "vsn4");

    assertEquals(
      new HashSet<ScanResultEntry>(Arrays.asList(
        ScanResultEntry.forAggregate(DigestUtils.md5Hex("vsn1" + "vsn4"), createAttrMap("2009-06-06", "a")),
        ScanResultEntry.forAggregate(DigestUtils.md5Hex("vsn2"), createAttrMap("2009-06-07", "b")),
        ScanResultEntry.forAggregate(DigestUtils.md5Hex("vsn3"), createAttrMap("2009-06-06", "c"))
      )),
      new HashSet<ScanResultEntry>(builder.toDigests()));
  }

  /**
   * The idea behind this is that a bucket should be immutable after it
   * has been digested, hence adding a new item to the same bucket
   * after it has been digested should result in an error.
   */
  @Test
  public void bucketsShouldBeSealedAfterQuery() {
    DigestBuilder builder = new DigestBuilder(aggregations);
    builder.add("id0", createAttrMap(JUN_6_2009_1, "a"), "vsn0");

    builder.toDigests();

    try {
      builder.add("id1", createAttrMap(JUN_6_2009_1, "a"), "vsn1");
      fail("Expected to provoke SealedBucketException");
    } catch (SealedBucketException e) {
    }
  }

  private static Map<String, String> createAttrMap(DateTime bizDate, String ss) {
    return createAttrMap(bizDate.toString(), ss);
  }
  private static Map<String, String> createAttrMap(String bizDate, String ss) {
    Map<String, String> attrs = new HashMap<String, String>();
    attrs.put("bizDate", bizDate.toString());
    attrs.put("someString", ss);
    return attrs;
  }
}