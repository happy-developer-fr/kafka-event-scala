package fr.happy.developer.domain.event

case class EventHandleResult(eventHandlerName: String, error: Option[EventHandleError]) {
  def isOk: Boolean = {
    error.isEmpty
  }

  def hasError: Boolean = {
    !isOk
  }
}

case class EventHandleResults(results: Seq[EventHandleResult]) {
  def hasError: Boolean = {
    !areOk
  }

  def areOk: Boolean = {
    results.forall(_.isOk)
  }

  def size: Int = {
    results.size
  }

  def errors: EventHandleResults = {
    EventHandleResults(results.filter(_.hasError))
  }

  def printErrors: String = {
    results.map(r => s"handler : ${r.eventHandlerName}, error : ${r.error}").foldLeft("")((s1, s2) => s1 + " " + s2)
  }
}

case class EventHandleError(message: String)