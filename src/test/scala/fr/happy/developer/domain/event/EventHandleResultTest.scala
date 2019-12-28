package fr.happy.developer.domain.event

import org.scalatestplus.play.PlaySpec

class EventHandleResultTest extends PlaySpec {
  private val aHandlerError = Some(EventHandleError("an error"))

  private val noErrorResult = EventHandleResult("A handler", None)
  private val anErrorResult = EventHandleResult("A handler", aHandlerError)
  "EventHandlerResult" should {
    "BeOk when error is None" in {
      assert(noErrorResult.isOk)
    }

    "BeError when error is defined" in {
      assert(anErrorResult.hasError)
    }
  }

  "EventHandlerResults" should {
    "BeOk when errors are empty " in {
      assert(EventHandleResults(Seq()).areOk)
      assert(EventHandleResults(Seq()).size==0)
    }

    "Print error when errors are empty" in {
      assert(EventHandleResults(Seq()).printErrors.equals(""))
    }

    "BeOk when errors contains only sucess " in {
      assert(EventHandleResults(Seq(noErrorResult, noErrorResult)).areOk)
    }

    "BeError when then is one errors" in {
      assert(EventHandleResults(Seq(anErrorResult)).hasError)
    }

    "BeError when then is multiples errors" in {
      assert(EventHandleResults(Seq(anErrorResult, anErrorResult)).hasError)
    }

    "BeError when then is one error and one sucess" in {
      assert(EventHandleResults(Seq(anErrorResult, noErrorResult)).hasError)
    }
  }
}
