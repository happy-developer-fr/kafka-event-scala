package fr.happy.developer.domain.event
import fr.happy.developer.domain.lag.waiter.LagWaiterRegisteredEventHandlerTest
import org.scalatestplus.play.PlaySpec

class EventHandlersTest extends PlaySpec {

  "Empty handler" should {
    "Do nothing when handle event" in {
      val lagWaitId: String = "Test"
      val results: EventHandleResults = EventHandlers.handleEvent(LagWaitRegisteredEvent(lagWaitId))
      assert(results.areOk)
    }
  }

  "Not empty handler" should {
    "Handle event" in {
      EventHandlers.registerHandler[LagWaitRegisteredEvent](classOf[LagWaitRegisteredEvent], LagWaiterRegisteredEventHandlerTest)

      val lagWaitId: String = "lw_1"

      val results: EventHandleResults = EventHandlers.handleEvent(LagWaitRegisteredEvent(lagWaitId))

      assert(results.results.head.eventHandlerName == LagWaiterRegisteredEventHandlerTest.Label)
    }
  }
}