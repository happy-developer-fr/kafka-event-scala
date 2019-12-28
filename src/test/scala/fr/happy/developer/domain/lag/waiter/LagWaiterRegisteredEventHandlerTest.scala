package fr.happy.developer.domain.lag.waiter

import fr.happy.developer.domain.event.EventHandleResult
import fr.happy.developer.domain.event.EventHandler
import fr.happy.developer.domain.event.LagWaitRegisteredEvent

object LagWaiterRegisteredEventHandlerTest extends EventHandler[LagWaitRegisteredEvent] {
  val Label: String = "testHandler"
  val eventHandleResult: EventHandleResult = EventHandleResult("testHandler", None)

  override def handle(event: LagWaitRegisteredEvent): EventHandleResult = {
    println(event)
    eventHandleResult
  }
}