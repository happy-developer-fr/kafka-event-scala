package fr.happy.developer.kafka.event.consumer

import fr.happy.developer.domain.event.EventHandleResults
import fr.happy.developer.kafka.event.KafkaEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class SingleKafkaEventProcessor(functionForEvent: Map[String, String => EventHandleResults]) {
  val emptyResult: EventHandleResults = EventHandleResults(Seq())

  protected val logger: Logger = LoggerFactory.getLogger(classOf[KafkaEventConsumer])

  def apply(event: KafkaEvent): EventHandleResults = {
    functionForEvent.get(event.key).map(f => f.apply(event.data)).getOrElse {
      logger.info(s"Event with name ${event.key} and data ${event.data} has been ignored by kafkassist")
      emptyResult
    }
  }
}
