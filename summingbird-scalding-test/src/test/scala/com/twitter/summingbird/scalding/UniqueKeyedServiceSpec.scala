package com.twitter.summingbird.scalding

import com.twitter.algebird.OptionMonoid
import com.twitter.scalding._
import com.twitter.scalding.commons.source.VersionedKeyValSource
import com.twitter.summingbird.batch.Timestamp
import com.twitter.summingbird.scalding.service._

import org.specs2.mutable._

/**
 * Test Job for UniqueKeyedService
 */
class UniqueKeyJoinJob(args: Args) extends Job(args) {
  import TDsl._

  // K: Int, W: Long, V: String
  val input = TypedTsv[(Timestamp, (Int, Long))]("input0") // left side of the join
  val serv = TypedTsv[(Int, String)]("input1") // service to be looked up
  val versionedSource = VersionedKeyValSource[Int, String]("input2") // source to be looked up

  val uniqueKeyJoiner = UniqueKeyedService.fromAndThen[(Int, String), Int, String](
    dr => versionedSource,
    pipe => pipe.map {
      case (key, value) => (key, value)
    },
    inputReducers = Some(1),
    requireFullySatisfiable = false
  )

  val output = uniqueKeyJoiner
    .doJoin(TypedPipe.from(input), TypedPipe.from(serv))
    .map[(Int, (Long, Option[String]))] {
      case (t, (k, (w, optV))) => (k, (w, optV))
    }
    .write(TypedTsv[(Int, (Long, Option[String]))]("output"))
}

/**
 * Test for UniqueKeyedService using UniqueKeyJoinJob
 */
class UniqueKeyedServiceSpec extends Specification {

  val input = List(
    (Timestamp(1001), (1001, 300L)),
    (Timestamp(1003), (1002, 200L)),
    (Timestamp(1003), (1003, 400L))
  )

  val service = List(
    (1002, "b-1002"),
    (1001, "a-1001")
  )
  val typedSource = service.toMap

  val expectedResult = Set(
    (1001, (300L, Some("a-1001"))),
    (1002, (200L, Some("b-1002"))),
    (1003, (400L, None))
  )

  "A multikey join service" should {
    "correctly join with multiple keys" in {

      JobTest(new UniqueKeyJoinJob(_))
        .source(TypedTsv[(Timestamp, (Int, Long))]("input0"), input)
        .source(TypedTsv[(Int, String)]("input1"), service)
        .source(VersionedKeyValSource[Int, String]("input2"), typedSource)
        .sink[(Int, (Long, Option[String]))](TypedTsv[(Int, (Long, Option[String]))]("output")) { outBuf =>
          // Make sure the doJoin outputs exact number of records for the inputs.
          input.size must be_==(outBuf.size)
          // Make sure the result is exact as expected.
          outBuf.toSet must be_==(expectedResult)
        }
        .run
        .finish
    }
  }
}
