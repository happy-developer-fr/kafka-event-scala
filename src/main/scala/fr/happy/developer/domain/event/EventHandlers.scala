package fr.happy.developer.domain.event

import com.github.ghik.silencer.silent

import scala.collection.mutable.{HashMap => MutableHashMap}
import scala.collection.mutable.{MultiMap => MutableMuLtiMap}
import scala.collection.mutable.{Set => MutableSet}

object EventHandlers {
  val eventHandlers = new MutableHashMap[String, MutableSet[EventHandler[_]]] with MutableMuLtiMap[String, EventHandler[_]]

  @silent
  def registerHandler[E](clazz: Class[_], handler: EventHandler[E]): Unit = {
    eventHandlers.addBinding(clazz.getName, handler)
  }

  def registerHandlers[E](clazz: Class[_], handlers: Seq[EventHandler[E]]): Unit = {
    handlers.foreach(registerHandler(clazz, _))
  }

  def handleEvent[E <: Event[_]](event: E): EventHandleResults = {
    EventHandleResults(eventHandlers.getOrElse(event.getClass.getName, MutableSet[EventHandler[E]]())
      .map(eventHandler => eventHandler.asInstanceOf[EventHandler[E]].handle(event))
      .toSeq)
  }
}
