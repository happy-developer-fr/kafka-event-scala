package fr.happy.developer.kafka.event.consumer

import java.util.Properties

import fr.happy.developer.domain.event.EventHandleResults
import fr.happy.developer.kafka.event.KafkaEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

class KafkaEventConsumer(eventTopic: String, properties: Properties, kafkaEventProcessor: SingleKafkaEventProcessor) extends SimpleKafkaConsumer(eventTopic, properties) {
  protected val logger: Logger = LoggerFactory.getLogger(classOf[KafkaEventConsumer])

  override protected def processRecords(records: ConsumerRecords[String, String]): Unit = {
    records.asScala.foreach(processRecord)
  }

  private def processRecord(record: ConsumerRecord[String, String]): Unit = {
    val results: EventHandleResults = kafkaEventProcessor(KafkaEvent(record.key, record.value))
    if (results.hasError) {
      logger.error("Error when processing records")
      throw new KafkaProcessingException(results.printErrors)
    }
  }
}