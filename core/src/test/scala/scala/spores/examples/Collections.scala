package scala.spores.examples

import scala.spores._
import Conversions._

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(classOf[JUnit4])
class CaptureSpec {
  @Test def `Example in serialization tutorial` = {
    val s = spore {
      val capturedInt = 8
      val capturedString = "Hello, World!"
      val capturedList = List(1, 2, 3, 4)
      (i: Int) =>
      {
        println(capturedString)
        capturedList.map(_ + i).contains(capturedInt)
      }
    }
    assert(!s(1))
    assert(!s(2))
    assert(!s(3))
    assert(s(4))
  }
}
