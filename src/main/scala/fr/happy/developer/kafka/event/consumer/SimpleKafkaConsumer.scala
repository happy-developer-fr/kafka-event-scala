package fr.happy.developer.kafka.event.consumer

import java.lang.Thread.UncaughtExceptionHandler
import java.time.Instant
import java.time.{Duration => JavaDuration}
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeoutException

import com.github.ghik.silencer.silent
import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Random
import scala.util.control.NonFatal

@silent
abstract class SimpleKafkaConsumer(
    val topic: String,
    val kafkaConsumerProps: Properties,
    val keyDeserializer: Deserializer[String] = new StringDeserializer,
    val valueDeserializer: Deserializer[String] = new StringDeserializer,
    val pollTimeout: Duration = SimpleKafkaConsumer.pollTimeout,
    val restartOnExceptionDelay: Duration = SimpleKafkaConsumer.restartOnExceptionDelay,
    val commitOffsetTimeout: Duration = SimpleKafkaConsumer.commitOffsetTimeout,
    val metrics: ConsumerMetrics = SimpleConsumerMetrics,
    val maxRestartsInIntervalDueToExceptions: Int = SimpleKafkaConsumer.maxRestartsInIntervalDueToExceptions,
    val restartDueToExceptionsInterval: Duration = SimpleKafkaConsumer.restartDueToExceptionsInterval) {
  protected val log = LoggerFactory.getLogger(this.getClass)

  private val lock = new Object
  private val shutdownPromise = promise[Unit]
  @volatile private var shutdownRequested = false
  @volatile private var terminatedPrematurely = false
  @volatile private var currentKafkaConsumer: Option[KafkaConsumer[String, String]] = None
  @volatile private var currentPartitionCount: Option[Int] = None
  @volatile private var isPollingThreadRunning = false

  /**
    * Calculates how long `onPartitionRevoked()` should block for to avoid split brain scenarios.
    *
    * @param maxMessageProcessingDuration maximum time allowance to process messages before
    *                                     an exception is thrown
    * @return
    */
  protected def minIsolationDetectionDuration(maxMessageProcessingDuration: Duration): Duration = {
    pollTimeout + maxMessageProcessingDuration + commitOffsetTimeout
  }

  /**
    * SimpleKafkaConsumer is considered to be `terminated` after `shutdown()` has been called
    * and the polling thread has stopped.
    *
    * @return true when the polling thread has stopped after a call to `shutdown()`
    */
  final def hasTerminated = (shutdownRequested || terminatedPrematurely) && !isPollingThreadRunning

  /**
    * Starts the polling thread. Once started, the consumer must be `shutdown()` to terminate.
    *
    * Any unhandled exceptions will cause the underlying Kafka consumer to be re-started after
    * the specified `restart-on-exception-delay` interval plus a random offset.
    *
    * If we experience more restarts than `maxRestartsInIntervalDueToExceptions` in the time
    * interval `restartDueToExceptionsInterval`, the consumer will crash. You can optionally
    * specify the handler for these cases.
    *
    * @param uncaughtExceptionHandler handler to be called if we restart to often in a small time interval
    */
  final def start(uncaughtExceptionHandler: Option[UncaughtExceptionHandler] = None): Unit = lock.synchronized {
    if (isPollingThreadRunning) throw new IllegalStateException("Already running.")
    if (shutdownPromise.isCompleted) throw new IllegalStateException("Was shutdown.")

    log.info("Starting polling thread...")
    isPollingThreadRunning = true

    val t = new Thread() {
      override def run(): Unit = {
        try {
          backoffOnUnhandledExceptionLoop()
        } catch {
          case e: Throwable =>
            lock.synchronized {
              terminatedPrematurely = true
            }
            throw e
        } finally {
          log.info("Shutting down polling thread.")
          lock.synchronized {
            isPollingThreadRunning = false
            shutdownPromise.success(Unit)
          }
        }
      }
    }
    uncaughtExceptionHandler.foreach(h => t.setUncaughtExceptionHandler(h))
    t.start()
  }

  /**
    * Will cause the polling thread to shutdown.
    *
    * @return a future that will becomes complete when shutdown is finished
    */
  def shutdown(): Future[Unit] = lock.synchronized {
    shutdownRequested = true
    currentKafkaConsumer.foreach(_.wakeup())
    lock.notifyAll()
    shutdownPromise.future
  }

  /**
    * Get the number of partitions for the kafka topic.
    *
    * This intentionally does NOT fetch the value fresh when this method is called but uses a
    * cached value that is fetched once every time the polling thread connects or reconnects
    * to the underlying consumer.
    *
    * This means that if the polling thread has not yet started or the first connect has not
    * yet succeeded, this will return None.
    *
    * This also means that the number of partitions returned here may be out of date if the
    * number of partitions on Kafka is changed. Because of this, any service using this
    * should be restarted to handle a change in the number of partitions.
    */
  def partitionCount: Option[Int] = {
    currentPartitionCount
  }

  /**
    * Get the amount of time to sleep after an exception
    *
    * This function is exposed to be altered in a subclass.
    *
    * @param exception raised during poll loop
    */
  def restartOnExceptionDelayDuration(exception: Throwable): Duration = {
    val randomDelay = Random.nextInt(restartOnExceptionDelay.toSeconds.toInt).seconds
    restartOnExceptionDelay + randomDelay
  }

  /**
    * This is the only method you need to implement in order to start consuming messages using
    * SimpleKafkaConsumer. The consumer offset is committed after every successful invocation
    * of this method. If this method throws and exception, all of the records in the failed
    * batch will be eventually retried.
    *
    * Any unhandled exceptions will cause the underlying Kafka consumer to be re-started after
    * the specified `restart-on-exception-delay` interval plus a random offset.
    *
    * To prevent auto-restart, you may be tempted to explicitly call `shutdown()` from inside your
    * exception handler. However, doing this will shutdown the SimpleKafkaConsumer permanently,
    * leaving your application in a degraded state.
    *
    * @param records result of polling Kafka, may be empty
    */
  protected def processRecords(records: ConsumerRecords[String, String]): Unit

  /**
    * Shutdown any custom resources.
    */
  protected def shutdownResources(): Unit = {}

  /**
    * Allows to inject custom ConsumerRebalanceListener. The listener code runs on the polling
    * thread, as described in
    * [[http://kafka.apache.org/090/javadoc/org/apache/kafka/clients/consumer/ConsumerRebalanceListener.html Kafka documentation]]
    *
    * @return an instance of ConsumerRebalanceListener
    */
  protected def makeRebalanceListener(): ConsumerRebalanceListener = {
    new LoggingRebalanceListener(topic, log) {
      override protected def logNewAssignment(partitions: Set[TopicPartition]): Unit = {
        val partitioningInfo = getTopicPartitioningInfo(partitions, maxLength = Some(16))
        setCurrentThreadDescription(partitioningInfo)
      }
    }
  }

  private def backoffOnUnhandledExceptionLoop(): Unit = {
    var exceptionTimeBuffer = List[Instant]()
    while (!shutdownRequested) {
      try {
        initializeConsumerAndEnterPollLoop()
      } catch {
        case NonFatal(e) =>
          val now = Instant.now
          exceptionTimeBuffer = now :: exceptionTimeBuffer
          val startOfInterval = now.minusMillis(restartDueToExceptionsInterval.toMillis)
          exceptionTimeBuffer = exceptionTimeBuffer.filter(_.isAfter(startOfInterval))

          log.info(
            s"Exceptions in the past ${restartDueToExceptionsInterval.toSeconds}s: ${exceptionTimeBuffer.length}, max: ${maxRestartsInIntervalDueToExceptions}"
          )
          if (exceptionTimeBuffer.length > maxRestartsInIntervalDueToExceptions) {
            log.error(
              s"Too many exceptions thrown in the past ${restartDueToExceptionsInterval.toSeconds}s, stopping",
              e
            )
            throw e
          }

          logExceptionAndDelayRestart(e)
        case fatal: Throwable =>
          log.error("Fatal error in polling thread.", fatal)
          throw fatal
      }
    }
  }

  private def initializeConsumerAndEnterPollLoop(): Unit = {
    try {
      setCurrentThreadDescription("initializing")
      currentKafkaConsumer = Some(makeKafkaConsumer())
      currentPartitionCount = currentKafkaConsumer.map(_.partitionsFor(topic).size)
      initializeKafkaConsumer(currentKafkaConsumer.get)
      pollLoop(currentKafkaConsumer.get)
    } catch {
      case e: WakeupException if shutdownRequested =>
      // This is expected, suppress the exception and exit the loop.
    } finally {
      log.info("Stopping Kafka consumer.")
      shutdownResources()
      currentKafkaConsumer.foreach(_.close())
      currentKafkaConsumer = None
      setCurrentThreadDescription("stopped")
    }
  }

  private def pollLoop(kafkaConsumer: KafkaConsumer[String, String]): Unit = {
    while (!shutdownRequested) {
      pollKafkaConsumer(kafkaConsumer)
    }
  }

  private def pollKafkaConsumer(kafkaConsumer: KafkaConsumer[String, String]): Unit = {
    val records = kafkaConsumer.poll(JavaDuration.ofMillis(pollTimeout.toMillis))
    processRecords(records)
    syncCommitOffset(kafkaConsumer)
  }

  private def syncCommitOffset(kafkaConsumer: KafkaConsumer[String, String]): Unit = {
    implicit val executionContext = waitThreadExecutor
    val commitOffsetFuture = Future {
      scala.concurrent.blocking {
        // TODO: We should look into using `commitAsync` once Kafka consumer 1.0 is out.
        // Sadly, kafkaConsumer.commitAsync(callback) never completes the callback.
        // So we are using this as a workaround for now.
        metrics.timeCommitSync {
          kafkaConsumer.commitSync()
        }
      }
    }

    try {
      Await.result(commitOffsetFuture, commitOffsetTimeout)
    } catch {
      case e: TimeoutException =>
        commitOffsetFuture.onComplete { _ =>
          kafkaConsumer.close()
        }
        shutdownResources()
        currentKafkaConsumer = None
        throw e
    }
  }

  private val waitThreadExecutor: ExecutionContext = {
    val threadFactory = new ThreadFactory {
      override def newThread(r: Runnable): Thread = {
        val thread = Executors.defaultThreadFactory().newThread(r)
        thread.setName(s"WaiterThread${thread.getId}")
        thread.setDaemon(true)
        thread
      }
    }
    val executorService = Executors.newFixedThreadPool(2, threadFactory)
    ExecutionContext.fromExecutorService(executorService)
  }

  private def logExceptionAndDelayRestart(exception: Throwable): Unit = {
    val restartDelayWithRandomOffset = restartOnExceptionDelayDuration(exception)
    log.error(
      "Unhandled exception, restarting kafka consumer " +
        s"in $restartDelayWithRandomOffset. Exception class: ${exception.getClass()}",
      exception
    )
    setCurrentThreadDescription("awaiting restart")
    sleepWithInterrupt(restartDelayWithRandomOffset)
  }

  private def sleepWithInterrupt(sleepDuration: Duration): Unit = {
    try {
      lock.synchronized {
        if (!shutdownRequested) lock.wait(sleepDuration.toMillis)
      }
    } catch {
      case e: InterruptedException =>
      // This is expected, suppress the exception and return.
    }
  }

  private def makeKafkaConsumer(): KafkaConsumer[String, String] = {
    val props = overwriteConsumerProperties(kafkaConsumerProps)
    new KafkaConsumer[String, String](props, keyDeserializer, valueDeserializer)
  }

  private def initializeKafkaConsumer(kafkaConsumer: KafkaConsumer[String, String]): Unit = {
    kafkaConsumer.subscribe(Seq(topic), makeRebalanceListener())
  }

  private def overwriteConsumerProperties(consumerProps: Properties): Properties = {
    val props = consumerProps.clone().asInstanceOf[Properties]
    props.put("enable.auto.commit", "false")
    props
  }

  private def setCurrentThreadDescription(status: String): Unit = {
    Thread.currentThread.setName(s"TopicConsumer: ${topic} [$status]")
  }
}

@silent
object SimpleKafkaConsumer {

  /** Default poll timeout */
  val pollTimeout: Duration = 1 second

  /** Default restart delay */
  val restartOnExceptionDelay: Duration = 5 seconds

  /** Default commit offset timeout */
  val commitOffsetTimeout: Duration = 5 seconds

  /** Default maximum restarts in the restart interval
    * ie. allow a maximum of 5 restarts in 60 seconds */
  val maxRestartsInIntervalDueToExceptions: Int = 5
  val restartDueToExceptionsInterval: Duration = 60 seconds

  /** Helper to create basic properties */
  def makeProps(bootstrapServer: String, consumerGroup: String, maxPollRecords: Option[Int] = None): Properties = {
    val props = new Properties()
    props.put("group.id", consumerGroup)
    props.put("bootstrap.servers", bootstrapServer)
    // You can tune this down, but for a single consumer process it's actually not too important
    // - if you consume a single topic, you'll save this times partitions in memory which is
    // usually a big "meh".
    props.put("max.partition.fetch.bytes", ((4 * 1024 * 1024) + 50000).toString)

    maxPollRecords.foreach { (v: Int) =>
      props.put("max.poll.records", v.toString)
    }

    props
  }
}

/**
  * A metrics sink for KafkaConsumer. Provide an implementation that calls the given method]
  * or leave the dummy version to not collect metrics.
  */
trait ConsumerMetrics {
  def timeCommitSync(f: => Unit): Unit
}

/**
  * Metrics implementation that does no actual timing metric.
  */
object SimpleConsumerMetrics extends ConsumerMetrics {

  override def timeCommitSync(f: => Unit): Unit = { f }
}